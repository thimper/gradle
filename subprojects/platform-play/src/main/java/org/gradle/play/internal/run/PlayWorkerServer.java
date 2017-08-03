/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.play.internal.run;

import org.gradle.api.Action;
import org.gradle.api.logging.Logging;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.classloader.ClassLoaderUtils;
import org.gradle.internal.classpath.DefaultClassPath;
import org.gradle.process.internal.worker.WorkerProcessContext;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.URLClassLoader;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class PlayWorkerServer implements Action<WorkerProcessContext>, PlayRunWorkerServerProtocol, ReloadListener, Serializable {

    private PlayRunSpec runSpec;
    private VersionedPlayRunAdapter runAdapter;

    private boolean reloadRequested;
    private boolean stopRequested;
    private volatile Lock lock = new ReentrantLock();
    private volatile Condition messageReady = lock.newCondition();

    public PlayWorkerServer(PlayRunSpec runSpec, VersionedPlayRunAdapter runAdapter) {
        this.runSpec = runSpec;
        this.runAdapter = runAdapter;
    }

    @Override
    public void execute(WorkerProcessContext context) {
        lock.lock();
        final PlayRunWorkerClientProtocol clientProtocol = context.getServerConnection().addOutgoing(PlayRunWorkerClientProtocol.class);
        context.getServerConnection().addIncoming(PlayRunWorkerServerProtocol.class, this);
        context.getServerConnection().connect();
        final PlayAppLifecycleUpdate result = start();
        try {
            clientProtocol.update(result);
            while (!stopRequested) {
                if (reloadRequested) {
                    clientProtocol.update(PlayAppLifecycleUpdate.reload());
                }
                messageReady.await();
            }
        } catch (InterruptedException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        } finally {
            clientProtocol.update(PlayAppLifecycleUpdate.stopped());
            lock.unlock();
        }
    }

    private PlayAppLifecycleUpdate start() {
        try {
            InetSocketAddress address = startServer();
            return PlayAppLifecycleUpdate.running(address);
        } catch (Exception e) {
            Logging.getLogger(this.getClass()).error("Failed to run Play", e);
            return PlayAppLifecycleUpdate.failed(e);
        }
    }

    private InetSocketAddress startServer() {
        ClassLoaderUtils.disableUrlConnectionCaching();
        final Thread thread = Thread.currentThread();
        final ClassLoader previousContextClassLoader = thread.getContextClassLoader();
        final ClassLoader classLoader = new URLClassLoader(new DefaultClassPath(runSpec.getClasspath()).getAsURLArray(), null);
        thread.setContextClassLoader(classLoader);
        try {
            Object buildDocHandler = runAdapter.getBuildDocHandler(classLoader, runSpec.getClasspath());
            Object buildLink = runAdapter.getBuildLink(classLoader, this, runSpec.getProjectPath(), runSpec.getApplicationJar(), runSpec.getChangingClasspath(), runSpec.getAssetsJar(), runSpec.getAssetsDirs());
            return runAdapter.runDevHttpServer(classLoader, classLoader, buildLink, buildDocHandler, runSpec.getHttpPort());
        } catch (Exception e) {
            throw UncheckedException.throwAsUncheckedException(e);
        } finally {
            thread.setContextClassLoader(previousContextClassLoader);
        }
    }

    @Override
    public void stop() {
        lock.lock();
        try {
            stopRequested = true;
            messageReady.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void upToDate(Throwable throwable) {
        runAdapter.upToDate(throwable);
    }

    @Override
    public void outOfDate() {
        runAdapter.outOfDate();
    }

    @Override
    public void reloadRequested() {
        System.out.println("reload requested");
        lock.lock();
        try {
            reloadRequested = true;
            messageReady.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
