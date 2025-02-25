/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package org.openjdk.skara.gradle.module;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;

public class ModulePlugin implements Plugin<Project> {
    public void apply(Project project) {
        project.apply(Map.of("plugin", "java-library"));
        var extension = project.getExtensions().create("module", ModuleExtension.class, project);

        project.afterEvaluate(p -> {
            for (var task : project.getTasksByName("compileJava", false)) {
                if (task instanceof JavaCompile compileJavaTask) {
                    compileJavaTask.doFirst(new Action<>() {
                        @Override
                        public void execute(Task at) {
                            var t = (JavaCompile) at;
                            var classpath = compileJavaTask.getClasspath().getAsPath();
                            compileJavaTask.getOptions().getCompilerArgs().addAll(List.of("--module-path", classpath));
                            compileJavaTask.setClasspath(project.files());
                        }
                    });
                }
            }

            for (var task : project.getTasksByName("compileTestJava", false)) {
                if (task instanceof JavaCompile compileTestJavaTask) {
                    compileTestJavaTask.doFirst(new Action<>() {
                        @Override
                        public void execute(Task at) {
                            var maybeModuleName = extension.getName();
                            if (!maybeModuleName.isPresent()) {
                                throw new GradleException("project " + p.getName() + " has not set ext.moduleName");
                            }
                            var moduleName = maybeModuleName.get();
                            var testSourceSet = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets().getByName("test");
                            var testSourceDirs = testSourceSet.getAllJava().getSrcDirs().stream().map(File::toString).collect(Collectors.joining(":"));
                            var classpath = compileTestJavaTask.getClasspath().getAsPath();

                            var opts = new ArrayList<>(compileTestJavaTask.getOptions().getCompilerArgs());
                            opts.addAll(List.of(
                                    "--module-path", classpath,
                                    "--patch-module", moduleName + "=" + testSourceDirs
                            ));

                            for (var module : extension.getRequires()) {
                                opts.add("--add-modules");
                                opts.add(module);
                                opts.add("--add-reads");
                                opts.add(moduleName + "=" + module);
                            }

                            compileTestJavaTask.getOptions().setCompilerArgs(opts);
                            compileTestJavaTask.setClasspath(project.files());
                        }
                    });
                }
            }

            for (var task : project.getTasksByName("test", false)) {
                if (task instanceof Test testTask) {
                    testTask.doFirst(new Action<>() {
                        @Override
                        public void execute(Task at) {
                            var t = (Test) at;
                            var maybeModuleName = extension.getName();
                            if (!maybeModuleName.isPresent()) {
                                throw new GradleException("project " + p.getName() + " has not set ext.moduleName");
                            }
                            var moduleName = maybeModuleName.get();
                            var testSourceSet = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets().getByName("test");
                            var outputDir = testSourceSet.getJava().getClassesDirectory().get().toString();
                            var classpath = testTask.getClasspath().getAsPath();

                            var jvmArgs = new ArrayList<>();
                            if (testTask.getJvmArgs() != null) {
                                jvmArgs.addAll(testTask.getJvmArgs());
                            }
                            jvmArgs.addAll(List.of(
                                    "-Djunit.jupiter.extensions.autodetection.enabled=true",
                                    "--module-path", classpath,
                                    "--add-modules", "ALL-MODULE-PATH",
                                    "--patch-module", moduleName + "=" + outputDir
                            ));

                            var opens = extension.getOpens();
                            for (var pkg : opens.keySet()) {
                                var modules = opens.get(pkg);
                                for (var module : modules) {
                                    jvmArgs.add("--add-opens");
                                    jvmArgs.add(moduleName + "/" + pkg + "=" + module);
                                }
                            }

                            for (var module : extension.getRequires()) {
                                jvmArgs.add("--add-reads");
                                jvmArgs.add(moduleName + "=" + module);
                            }

                            testTask.setJvmArgs(jvmArgs);
                            testTask.setClasspath(project.files());
                        }
                    });
                }
            }
        });
    }
}
