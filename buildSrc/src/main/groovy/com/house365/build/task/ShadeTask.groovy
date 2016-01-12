/*
 * Copyright (c) 2015-2016, House365. All rights reserved.
 */

package com.house365.build.task

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.diagnostics.internal.dependencies.AsciiDependencyReportRenderer
import org.gradle.logging.StyledTextOutputFactory

/**
 * User: Ralf Wondratschek
 */
class ShadeTask extends DefaultTask {
    private AsciiDependencyReportRenderer renderer = new AsciiDependencyReportRenderer();

    ShadeTask() {
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    @TaskAction
    def taskAction() {
        println "ShadeTask.taskAction"
//        project.configurations.shade.copyRecursive { dep -> dep.name == 'okhttp' }
//                .each { file -> println file.name }
//        println()
//        project.configurations.shade.files { dep -> dep.name == 'okhttp' }
//                .each { file -> println file.name }
//        println()
//        println()
//        project.configurations.compile.copyRecursive { dep ->
//            println dep.name
//        }.each {
//            file -> println file.name
//        }
//        project.configurations.compile.files.each { file -> println file.name }

        println "1234567890"
        project.configurations.shade.copyRecursive {
            println it.getClass()
        }
        println "1234567890"


        def configuration = project.configurations.provided

        ResolvedComponentResult result = configuration.getIncoming().getResolutionResult().getRoot();
        showConfigurationDependencies(result, 0)

        println ""
        renderer.setOutput(getServices().get(StyledTextOutputFactory.class).create(getClass()));
        renderer.startProject(project);
        renderer.startConfiguration(configuration);
        renderer.render(configuration);
        renderer.completeConfiguration(configuration);
    }

    def void showConfigurationDependencies(ResolvedComponentResult result, int level) {
        Iterator i = result.getDependencies().iterator();
        while (i.hasNext()) {
            DependencyResult d = (DependencyResult) i.next();
            for (int j = 0; j < level; j++) {
                print "   "
            }
            print " \\---"
            println d

            if (d instanceof UnresolvedDependencyResult) {

            } else {
                showConfigurationDependencies(((ResolvedDependencyResult) d).getSelected(), level + 1)
            }
        }
    }

    def String getName(UnresolvedDependencyResult dependencyResult) {
        ComponentSelector requested = dependencyResult.getRequested();
        ComponentSelector attempted = dependencyResult.getAttempted();

        if (requested.equals(attempted)) {
            println requested.getDisplayName();
            return
        }

        if (requested instanceof ModuleComponentSelector && attempted instanceof ModuleComponentSelector) {
            ModuleComponentSelector requestedSelector = (ModuleComponentSelector) requested;
            ModuleComponentSelector attemptedSelector = (ModuleComponentSelector) attempted;

            if (requestedSelector.getGroup().equals(attemptedSelector.getGroup())
                    && requestedSelector.getModule().equals(attemptedSelector.getModule())
                    && !requestedSelector.getVersion().equals(attemptedSelector.getVersion())) {
                println requested.getDisplayName() + " -> " + ((ModuleComponentSelector) attempted).getVersion();
                return
            }
        }

        println requested.getDisplayName() + " -> " + attempted.getDisplayName();
        return
    }


}
