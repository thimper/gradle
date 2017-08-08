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

package org.gradle.nativeplatform.test.xctest.plugins;

import com.google.common.collect.Lists;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.file.CopySpec;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryVar;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.Exec;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.util.PatternSet;
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.swift.internal.DefaultSwiftComponent;
import org.gradle.language.swift.model.SwiftComponent;
import org.gradle.language.swift.plugins.SwiftBasePlugin;
import org.gradle.language.swift.tasks.SwiftCompile;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;
import org.gradle.nativeplatform.tasks.LinkExecutable;
import org.gradle.nativeplatform.test.xctest.internal.NativeTestExecuter;
import org.gradle.nativeplatform.toolchain.NativeToolChain;
import org.gradle.nativeplatform.toolchain.internal.NativeToolChainInternal;
import org.gradle.nativeplatform.toolchain.internal.NativeToolChainRegistryInternal;
import org.gradle.nativeplatform.toolchain.internal.PlatformToolProvider;

import javax.inject.Inject;
import java.util.Collections;

public class XCTestConventionPlugin implements Plugin<ProjectInternal> {
    private final ObjectFactory objectFactory;
    private final FileOperations fileOperations;

    @Inject
    public XCTestConventionPlugin(ObjectFactory objectFactory, FileOperations fileOperations) {
        this.objectFactory = objectFactory;
        this.fileOperations = fileOperations;
    }

    @Override
    public void apply(final ProjectInternal project) {
        project.getPluginManager().apply(SwiftBasePlugin.class);

        final DirectoryVar buildDirectory = project.getLayout().getBuildDirectory();
        Directory projectDirectory = project.getLayout().getProjectDirectory();
        ConfigurationContainer configurations = project.getConfigurations();
        TaskContainer tasks = project.getTasks();

        // Add the component extension
        SwiftComponent component = project.getExtensions().create(SwiftComponent.class, "xctest", DefaultSwiftComponent.class, fileOperations);
        component.getSource().from(projectDirectory.dir("src/test/swift"));

        // Add a compile task
        SwiftCompile compile = tasks.create("compileTestSwift", SwiftCompile.class);

        compile.includes(configurations.getByName(SwiftBasePlugin.SWIFT_TEST_IMPORT_PATH));

        FileCollection sourceFiles = component.getSwiftSource();
        compile.source(sourceFiles);

        // xcrun --show-sdk-platform-path => /Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform

        compile.setCompilerArgs(Lists.newArrayList("-g", "-F/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/Library/Frameworks/"));
        compile.setMacros(Collections.<String, String>emptyMap());
        compile.setModuleName(project.getName());

        compile.setObjectFileDir(buildDirectory.dir("test/objs"));

        DefaultNativePlatform currentPlatform = new DefaultNativePlatform("current");
        compile.setTargetPlatform(currentPlatform);

        // TODO - make this lazy
        NativeToolChain toolChain = project.getModelRegistry().realize("toolChains", NativeToolChainRegistryInternal.class).getForPlatform(currentPlatform);
        compile.setToolChain(toolChain);

        // Add a link task
        LinkExecutable link = tasks.create("linkTest", LinkExecutable.class);
        // TODO - need to set basename
        link.source(compile.getObjectFileDirectory().getAsFileTree().matching(new PatternSet().include("**/*.obj", "**/*.o")));
        link.lib(configurations.getByName(CppBasePlugin.NATIVE_TEST_LINK));
        link.setLinkerArgs(Lists.newArrayList("-Xlinker", "-bundle", "-F/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/Library/Frameworks/", "-framework", "XCTest", "-Xlinker", "-rpath", "-Xlinker", "@executable_path/../Frameworks", "-Xlinker", "-rpath", "-Xlinker", "@loader_path/../Frameworks"));
        PlatformToolProvider toolProvider = ((NativeToolChainInternal) toolChain).select(currentPlatform);
        Provider<RegularFile> exeLocation = buildDirectory.file(toolProvider.getExecutableName("exe/" + project.getName() + "Test"));
        link.setOutputFile(exeLocation);
        link.setTargetPlatform(currentPlatform);
        link.setToolChain(toolChain);


        Copy testBundle = tasks.create("createXcTestBundle", Copy.class);
        testBundle.dependsOn(link);
        testBundle.from(exeLocation.get().get(), new Action<CopySpec>() {
            @Override
            public void execute(CopySpec copySpec) {
                copySpec.into("Contents/MacOS");
            }
        });
        testBundle.from(project.file("src/test/resources/Info.plist"), new Action<CopySpec>() {
                @Override
                public void execute(CopySpec copySpec) {
                    copySpec.into("Contents");
                }
            });
        testBundle.setDestinationDir(project.file("build/" + project.getName() + "Test.xctest"));
//        testBundle.from()


            Exec swiftStdlibTool = tasks.create("swiftStdlib", Exec.class);
        swiftStdlibTool.dependsOn(testBundle);
        swiftStdlibTool.executable("/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/swift-stdlib-tool");
        swiftStdlibTool.args("--copy", "--scan-executable", exeLocation.get().get().getAbsolutePath(), "--destination", buildDirectory.dir(project.getName() + "Test.xctest/Contents/Frameworks").get().get().getAbsolutePath(), "--platform", "macosx",
            "--resource-destination", buildDirectory.dir(project.getName() + "Test.xctest/Contents/Resources").get().get().getAbsolutePath(), "--scan-folder", buildDirectory.dir(project.getName() + "Test.xctest/Contents/Frameworks").get().get().getAbsolutePath());

        // xcrun --find swift-stdlib-tool
        // xcrun --find xctest
        Test test = tasks.create("test", Test.class);
        test.dependsOn(swiftStdlibTool);
        test.setTestExecuter(objectFactory.newInstance(NativeTestExecuter.class, project.getServices()));
        test.getExtensions().getExtraProperties().set("testBinary", project.file("build/" + project.getName() + "Test.xctest"));
        test.getExtensions().getExtraProperties().set("workingDir", project.file("build"));
        test.setExecutable("java");
        test.setTestClassesDirs(project.files());
        test.setBinResultsDir(project.file("build/result"));
        test.setBootstrapClasspath(project.files());
        test.setClasspath(project.files());
        test.getReports().getHtml().setDestination(project.file("build/reports/bob"));
        test.getReports().getJunitXml().setDestination(project.file("build/reports/bobxml"));
    }
}
