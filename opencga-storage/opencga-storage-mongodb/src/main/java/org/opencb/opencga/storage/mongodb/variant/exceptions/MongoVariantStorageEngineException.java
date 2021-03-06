/*
 * Copyright 2015-2017 OpenCB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opencb.opencga.storage.mongodb.variant.exceptions;

import org.opencb.opencga.storage.core.exceptions.StorageEngineException;
import org.opencb.opencga.storage.core.variant.VariantStorageOptions;

import java.util.List;

/**
 * Created on 30/06/16.
 *
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public class MongoVariantStorageEngineException extends StorageEngineException {

    public MongoVariantStorageEngineException(String message, Throwable cause) {
        super(message, cause);
    }

    public MongoVariantStorageEngineException(String message) {
        super(message);
    }

    public static MongoVariantStorageEngineException filesBeingMergedException(List<Integer> fileIds) {
        return new MongoVariantStorageEngineException(
                "Files " + fileIds + " are already being loaded in the variants collection "
                        + "right now. To ignore this, relaunch with " + VariantStorageOptions.RESUME.key() + "=true");
    }

    public static MongoVariantStorageEngineException fileBeingStagedException(int fileId, String fileName) {
        return new MongoVariantStorageEngineException(
                "File \"" + fileName + "\" (" + fileId + ") is already being loaded in the stage collection "
                        + "right now. To ignore this, relaunch with " + VariantStorageOptions.RESUME.key() + "=true");
    }
}
