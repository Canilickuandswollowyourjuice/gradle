/*
 * Copyright 2013 the original author or authors.
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

package org.gradle.model.internal.core;

import java.util.Collections;

public class SingleTypeModelPromise implements ModelPromise {

    private final ModelType<?> type;

    public SingleTypeModelPromise(ModelType<?> type) {
        this.type = type;
    }

    public <T> boolean asWritable(ModelType<T> type) {
        return type.isAssignableFrom(this.type);
    }

    public <T> boolean asReadOnly(ModelType<T> type) {
        return type.isAssignableFrom(this.type);
    }

    public Iterable<String> getWritableTypeDescriptions() {
        return Collections.singleton(description(type));
    }

    public Iterable<String> getReadableTypeDescriptions() {
        return getWritableTypeDescriptions();
    }

    public static String description(ModelType<?> type) {
        return type.toString() + " (or assignment compatible type thereof)";
    }
}
