/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.instantexecution.serialization.codecs

import org.gradle.api.Project
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.initialization.Settings
import org.gradle.api.internal.artifacts.dsl.dependencies.ProjectFinder
import org.gradle.api.internal.artifacts.transform.ArtifactTransformActionScheme
import org.gradle.api.internal.artifacts.transform.ArtifactTransformListener
import org.gradle.api.internal.artifacts.transform.ArtifactTransformParameterScheme
import org.gradle.api.internal.file.FileCollectionFactory
import org.gradle.api.internal.file.FileOperations
import org.gradle.api.internal.file.FileResolver
import org.gradle.api.internal.file.collections.DirectoryFileTreeFactory
import org.gradle.api.internal.project.ProjectStateRegistry
import org.gradle.api.invocation.Gradle
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.util.internal.PatternSpecFactory
import org.gradle.execution.plan.TaskNodeFactory
import org.gradle.initialization.BuildRequestMetaData
import org.gradle.instantexecution.extensions.uncheckedCast
import org.gradle.instantexecution.serialization.Codec
import org.gradle.instantexecution.serialization.SerializerCodec
import org.gradle.instantexecution.serialization.ownerService
import org.gradle.instantexecution.serialization.reentrant
import org.gradle.instantexecution.serialization.unsupported
import org.gradle.internal.event.ListenerManager
import org.gradle.internal.fingerprint.FileCollectionFingerprinterRegistry
import org.gradle.internal.hash.ClassLoaderHierarchyHasher
import org.gradle.internal.isolation.IsolatableFactory
import org.gradle.internal.operations.BuildOperationExecutor
import org.gradle.internal.operations.BuildOperationListenerManager
import org.gradle.internal.reflect.Instantiator
import org.gradle.internal.serialize.BaseSerializerFactory.BOOLEAN_SERIALIZER
import org.gradle.internal.serialize.BaseSerializerFactory.BYTE_SERIALIZER
import org.gradle.internal.serialize.BaseSerializerFactory.CHAR_SERIALIZER
import org.gradle.internal.serialize.BaseSerializerFactory.DOUBLE_SERIALIZER
import org.gradle.internal.serialize.BaseSerializerFactory.FILE_SERIALIZER
import org.gradle.internal.serialize.BaseSerializerFactory.FLOAT_SERIALIZER
import org.gradle.internal.serialize.BaseSerializerFactory.INTEGER_SERIALIZER
import org.gradle.internal.serialize.BaseSerializerFactory.LONG_SERIALIZER
import org.gradle.internal.serialize.BaseSerializerFactory.PATH_SERIALIZER
import org.gradle.internal.serialize.BaseSerializerFactory.SHORT_SERIALIZER
import org.gradle.internal.serialize.BaseSerializerFactory.STRING_SERIALIZER
import org.gradle.internal.serialize.Serializer
import org.gradle.internal.serialize.SetSerializer
import org.gradle.internal.snapshot.ValueSnapshotter
import org.gradle.process.internal.ExecActionFactory
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import org.gradle.workers.WorkerExecutor
import org.gradle.workers.internal.IsolatableSerializerRegistry
import kotlin.reflect.KClass


class Codecs(
    directoryFileTreeFactory: DirectoryFileTreeFactory,
    fileCollectionFactory: FileCollectionFactory,
    fileResolver: FileResolver,
    instantiator: Instantiator,
    listenerManager: ListenerManager,
    projectStateRegistry: ProjectStateRegistry,
    taskNodeFactory: TaskNodeFactory,
    fingerprinterRegistry: FileCollectionFingerprinterRegistry,
    projectFinder: ProjectFinder,
    buildOperationExecutor: BuildOperationExecutor,
    classLoaderHierarchyHasher: ClassLoaderHierarchyHasher,
    isolatableFactory: IsolatableFactory,
    valueSnapshotter: ValueSnapshotter,
    fileCollectionFingerprinterRegistry: FileCollectionFingerprinterRegistry,
    isolatableSerializerRegistry: IsolatableSerializerRegistry,
    parameterScheme: ArtifactTransformParameterScheme,
    actionScheme: ArtifactTransformActionScheme,
    transformListener: ArtifactTransformListener
) {

    private
    val fileSetSerializer = SetSerializer(FILE_SERIALIZER)

    private
    val userTypeBindings = bindings {

        bind(unsupported<Project>())
        bind(unsupported<Gradle>())
        bind(unsupported<Settings>())
        bind(unsupported<TaskContainer>())
        bind(unsupported<ConfigurationContainer>())

        bind(STRING_SERIALIZER)
        bind(BOOLEAN_SERIALIZER)
        bind(INTEGER_SERIALIZER)
        bind(CHAR_SERIALIZER)
        bind(SHORT_SERIALIZER)
        bind(LONG_SERIALIZER)
        bind(BYTE_SERIALIZER)
        bind(FLOAT_SERIALIZER)
        bind(DOUBLE_SERIALIZER)
        bind(FileTreeCodec(fileSetSerializer, directoryFileTreeFactory))
        bind(FILE_SERIALIZER)
        bind(PATH_SERIALIZER)
        bind(ClassCodec)
        bind(MethodCodec)

        // Only serialize certain List implementations
        bind(arrayListCodec)
        bind(linkedListCodec)
        bind(ImmutableListCodec)

        // Only serialize certain Set implementations for now, as some custom types extend Set (eg DomainObjectContainer)
        bind(EnumSetCodec)
        bind(linkedHashSetCodec)
        bind(hashSetCodec)
        bind(treeSetCodec)
        bind(ImmutableSetCodec)

        // Only serialize certain Map implementations for now, as some custom types extend Map (eg DefaultManifest)
        bind(EnumMapCodec)
        bind(linkedHashMapCodec)
        bind(hashMapCodec)
        bind(treeMapCodec)
        bind(ImmutableMapCodec)

        bind(arrayCodec)
        bind(BrokenValueCodec)

        bind(ListenerBroadcastCodec(listenerManager))
        bind(LoggerCodec)

        bind(ConfigurableFileCollectionCodec(fileCollectionFactory))
        bind(FileCollectionCodec(fileCollectionFactory))

        // Dependency management types
        bind(ArtifactCollectionCodec)
        bind(TransformationNodeReferenceCodec)

        bind(DefaultCopySpecCodec(fileResolver, instantiator))
        bind(DestinationRootCopySpecCodec(fileResolver))

        bind(TaskReferenceCodec)

        bind(ownerService<ObjectFactory>())
        bind(ownerService<PatternSpecFactory>())
        bind(ownerService<FileResolver>())
        bind(ownerService<Instantiator>())
        bind(ownerService<FileCollectionFactory>())
        bind(ownerService<FileOperations>())
        bind(ownerService<BuildOperationExecutor>())
        bind(ownerService<ToolingModelBuilderRegistry>())
        bind(ownerService<ExecActionFactory>())
        bind(ownerService<BuildOperationListenerManager>())
        bind(ownerService<BuildRequestMetaData>())
        bind(ownerService<WorkerExecutor>())

        // This protects the BeanCodec against StackOverflowErrors but
        // we can still get them for the other codecs, for instance,
        // with deeply nested Lists, deeply nested Maps, etc.
        bind(reentrant(BeanCodec()))
    }

    val userTypesCodec = BindingsBackedCodec(userTypeBindings)

    private
    val internalTypeBindings = bindings {
        bind(INTEGER_SERIALIZER)

        bind(TaskNodeCodec(projectStateRegistry, userTypesCodec, taskNodeFactory))
        bind(InitialTransformationNodeCodec(buildOperationExecutor, transformListener))
        bind(ChainedTransformationNodeCodec(buildOperationExecutor, transformListener))
        bind(ResolvableArtifactCodec)
        bind(TransformationStepCodec(projectStateRegistry, fingerprinterRegistry, projectFinder))
        bind(DefaultTransformerCodec(buildOperationExecutor, classLoaderHierarchyHasher, isolatableFactory, valueSnapshotter, fileCollectionFactory, fileCollectionFingerprinterRegistry, isolatableSerializerRegistry, parameterScheme, actionScheme))
        bind(LegacyTransformerCodec(classLoaderHierarchyHasher, isolatableFactory, actionScheme))
        bind(ExecutionGraphDependenciesResolverCodec)
    }

    val internalTypesCodec = BindingsBackedCodec(internalTypeBindings)
}


private
inline fun bindings(block: BindingsBuilder.() -> Unit): List<Binding> =
    BindingsBuilder().apply(block).build()


private
class BindingsBuilder {

    private
    val bindings = mutableListOf<Binding>()

    fun build(): List<Binding> = bindings.toList()

    fun bind(type: Class<*>, codec: Codec<*>) {
        require(bindings.none { it.type === type })
        val tag = bindings.size
        require(tag < Byte.MAX_VALUE)
        bindings.add(
            Binding(tag.toByte(), type, codec.uncheckedCast())
        )
    }

    inline fun <reified T> bind(codec: Codec<T>) =
        bind(T::class.java, codec)

    inline fun <reified T> bind(serializer: Serializer<T>) =
        bind(T::class.java, serializer)

    fun bind(type: KClass<*>, codec: Codec<*>) =
        bind(type.java, codec)

    fun bind(type: KClass<*>, serializer: Serializer<*>) =
        bind(type.java, serializer)

    fun bind(type: Class<*>, serializer: Serializer<*>) =
        bind(type, SerializerCodec(serializer))
}
