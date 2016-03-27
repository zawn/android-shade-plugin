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

        def shade = project.configurations.shade
        shade.copyRecursive { dep -> dep.name == 'okhttp' }
                .each { file -> println file.name }
        println("\n 1 ")
        shade.files { dep -> dep.name == 'okhttp' }
                .each { file -> println file.name }
        println("\n 2 ")
        println("\n _releaseCompile ")

        def releaseCompile = project.configurations._releaseCompile
        releaseCompile.copyRecursive { dep ->
            println "555555555"
            println dep
        }

        println "\nshade:"
        shade.allDependencies.each { dependency ->
            println dependency
            println shade.files(dependency)
            println "\nFrom ReleaseCompile"

            def find = releaseCompile.allDependencies.find { dep -> dep.name == dependency.name && dep.group == dependency.group }
            println find
            println releaseCompile.files(find)
            println "\n\n"

        }
        println "1234567890"



        shade.allDependencies.each {
            println " it   \n" + it.toString()
            releaseCompile.files(it)
        }
        println "\n\n555666"
//        println "\n\nshade ResolutionResult().allDependencies"
//        shade.getIncoming().getResolutionResult().allDependencies.each {
//            println shade.files(it.getSelected())
//        }
        println "\n\n_releaseCompile ResolutionResult().allDependencies"
        releaseCompile.allDependencies.each {
            println releaseCompile.allDependencies.find { dep -> dep.name == 'okhttp' }
            println releaseCompile.files(it)
        }

        println "\n\nResolvedComponentResult:"

        ResolvedComponentResult result = shade.getIncoming().getResolutionResult().getRoot();
        showConfigurationDependencies(result, 0)

        println "\n\nRenderer:"
        renderer.setOutput(getServices().get(StyledTextOutputFactory.class).create(getClass()));
        renderer.startProject(project);
        renderer.startConfiguration(shade);
        renderer.render(shade);
        renderer.completeConfiguration(shade);
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


    def collectModuleComponents = {
        def dependencies = configurations.vendors.incoming.resolutionResult.allDependencies
        def moduleDependencies = dependencies.findAll {
            it instanceof ResolvedDependencyResult && it.requested instanceof ModuleComponentSelector
        }

        moduleDependencies*.selected.toSet()
    }

    def collectBinaryArtifacts = {
        def moduleComponents = collectModuleComponents()

        configurations.vendors.resolvedConfiguration.resolvedArtifacts.findAll { resolvedArtifact ->
            moduleComponents.any {
                resolvedArtifact.moduleVersion.id.equals(it.moduleVersion)
            }
        }
    }
}
