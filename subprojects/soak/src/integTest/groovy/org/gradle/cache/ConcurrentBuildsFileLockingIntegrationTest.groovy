/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.cache

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.daemon.DaemonLogsAnalyzer
import org.gradle.util.Requires
import org.gradle.util.TestPrecondition

class ConcurrentBuildsFileLockingIntegrationTest extends AbstractIntegrationSpec {

    // = file hash cache =
    // The lock lives in gradle user home scope
    //
    // LockMode.None = Lock on first use, release on demand
    //
    // The test simulates a non-responsive daemon by suspending the daemon JVM process.
    @Requires(TestPrecondition.UNIX_DERIVATIVE)
    def "a suspended daemon does not block the shared file hash cache"() {
        given:
        executer.requireDaemon()
        buildFile << ""

        when:
        executer.withDaemonBaseDir(file("daemon1")).withArguments("-d")
        succeeds "help"

        def daemon1Pid = DaemonLogsAnalyzer.newAnalyzer(executer.daemonBaseDir).daemon.context.pid
        "kill -SIGSTOP $daemon1Pid".execute() //put the first daemon to sleep
        Thread.sleep(500)

        executer.withDaemonBaseDir(file("daemon2")).withArguments("-d")

        then:
        succeeds "help"
    }

    // = artifact cache / artifact transform cache- runs in build session =
    // The lock lives in build session scope; it is initially acquired with the first dependency resolution
    //
    // LockMode.None = Lock on first use, release on demand
    //
    // The test simulates a non-responsive daemon by suspending the daemon JVM process.
    @Requires(TestPrecondition.UNIX_DERIVATIVE)
    def "a suspended daemon does not block the artifact cache"() {
        given:
        executer.requireDaemon()

        buildFile << """
            repositories { jcenter() }  
            configurations { conf }
            dependencies { conf "junit:junit:4.12" }
            task resolve { doLast { println configurations.conf.files } }
            task loopForever { doLast { while(true) { Thread.sleep(1000) } } }
        """

        when:
        executer.withDaemonBaseDir(file("daemon1")).withArguments("-d")
        def b1 = executer.withTasks("resolve", "loopForever").start()

        def daemonsFixture = DaemonLogsAnalyzer.newAnalyzer(executer.daemonBaseDir)
        while (daemonsFixture.daemons.isEmpty()) {
            Thread.sleep(500)
        }
        def daemon1Pid = daemonsFixture.daemon.context.pid

        buildFile << """
            task suspendOtherDaemon { doLast { "kill -SIGSTOP $daemon1Pid".execute() } }
        """

        executer.withDaemonBaseDir(file("daemon2")).withArguments("-d")

        then:
        succeeds "suspendOtherDaemon", "resolve"
    }

    // = workerMain classpath cache =
    // The lock lives in build session scope; it is initially acquired with the first action execution on a worker
    //
    // LockMode.Shared = Lock on first use and keep until service is closed
    //
    // FileChannel.tryLock(), which is used to request the shared lock, does not guarantee a shared lock and might return an exclusive lock.
    // The test simulates that by requesting the "workerMain" as Exclusive lock.
    def "daemon does not block main worker classpath cache if it was locked exclusively"() {
        given:
        executer.requireDaemon()
        buildFile << """
            import org.gradle.cache.CacheRepository
            import org.gradle.cache.internal.FileLockManager
            import org.gradle.cache.internal.filelock.LockOptionsBuilder
            
            task doWorkInWorker(type: WorkerTask) {
                withExclusiveLock = false
            }
            task doWorkInWorkerWithExclusiveLock(type: WorkerTask) {
                withExclusiveLock = true
            }
            
            class WorkerTask extends DefaultTask {
                @Input
                boolean withExclusiveLock = false
            
                @javax.inject.Inject
                WorkerExecutor getWorkerExecutor() { throw new UnsupportedOperationException() }
                
                @javax.inject.Inject
                CacheRepository getCacheRepository() { throw new UnsupportedOperationException() }
                
                @TaskAction
                void doWork() {
                    if (withExclusiveLock) {
                        //simulate exclusive lock
                        cacheRepository.cache("workerMain").withLockOptions(LockOptionsBuilder.mode(FileLockManager.LockMode.Exclusive)).open();
                    } else {
                        workerExecutor.submit(TestRunnable) { WorkerConfiguration config ->
                            config.isolationMode = IsolationMode.PROCESS
                        }
                    }
                }
            }

            class TestRunnable implements Runnable { void run() { } }
        """

        when:
        executer.withDaemonBaseDir(file("daemon1")).withArguments("-d")
        succeeds "doWorkInWorkerWithExclusiveLock"

        executer.withDaemonBaseDir(file("daemon2")).withArguments("-d")

        then:
        succeeds "doWorkInWorker"
    }

    // = Zinc compiler cache =
    // The lock lives in a Scala compiler daemon, it is only used during Zinc compiler setup (which can be long running if the cache is empty)
    //
    // LockMode.Exclusive = Lock is requested when required and released afterwards
    //
    // This test simulates a long running Zic compiler setup by running code similar to ZincScalaCompilerFactory through the worker API.
    def "a legit exclusive lock is not causing another process to timeout"() {
        given:
        executer.requireDaemon()
        buildFile << """
            import org.gradle.cache.CacheRepository
            import org.gradle.cache.PersistentCache
            import org.gradle.cache.internal.FileLockManager
            import org.gradle.cache.internal.filelock.LockOptionsBuilder
            import org.gradle.cache.internal.CacheRepositoryServices;
            import org.gradle.internal.nativeintegration.services.NativeServices;
            import org.gradle.internal.service.DefaultServiceRegistry;
            import org.gradle.internal.service.scopes.GlobalScopeServices;
            
            task doWorkInWorker(type: WorkerTask)
            
            class WorkerTask extends DefaultTask {
                @javax.inject.Inject
                WorkerExecutor getWorkerExecutor() { throw new UnsupportedOperationException() }
                
                @TaskAction
                void doWork() {
                    workerExecutor.submit(ToolSetupRunnable) { WorkerConfiguration config ->
                        config.isolationMode = IsolationMode.PROCESS
                    }
                    workerExecutor.submit(ToolSetupRunnable) { WorkerConfiguration config ->
                        config.isolationMode = IsolationMode.PROCESS
                    }
                }
            }

            class ToolSetupRunnable implements Runnable {
                void run() {
                    CacheRepository cacheRepository = ZincCompilerServices.getInstance(new File("home")).get(CacheRepository.class);
                    println "Waiting for lock..."
                    final PersistentCache zincCache = cacheRepository.cache("zinc-0.3.15")
                            .withDisplayName("Zinc 0.3.15 compiler cache")
                            .withLockOptions(LockOptionsBuilder.mode(FileLockManager.LockMode.Exclusive))
                            .open();
                    println "Starting work..."
                    try {
                        Thread.sleep(100000) //setup an external tool which can take some time
                    } finally {
                        zincCache.close();
                    }
                }
            }
            
            class ZincCompilerServices extends DefaultServiceRegistry {
                private static ZincCompilerServices instance;
        
                private ZincCompilerServices(File gradleUserHome) {
                    super(NativeServices.getInstance());
        
                    addProvider(new GlobalScopeServices(true));
                    addProvider(new CacheRepositoryServices(gradleUserHome, null));
                }
        
                public static ZincCompilerServices getInstance(File gradleUserHome) {
                    if (instance == null) {
                        NativeServices.initialize(gradleUserHome);
                        instance = new ZincCompilerServices(gradleUserHome);
                    }
                    return instance;
                }
            }
        """

        when:
        executer.withDaemonBaseDir(file("daemon1")).withArguments("-d")

        then:
        succeeds "doWorkInWorker"
    }
}
