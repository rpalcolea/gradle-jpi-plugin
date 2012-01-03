/*
 * Copyright 2011 the original author or authors.
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

package org.jenkinsci.gradle.plugins.jpi

import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logger
import org.gradle.util.ConfigureUtil

/**
 * Information on a single developer for the <developer> tag in the output POM.
 *
 * @author Andrew Bayer
 */

class JpiDeveloper {
    private legalFields = ['id', 'name', 'email', 'url', 'organization',
                           'organizationUrl', 'timezone'];

    private fields = [:]
                           
    private Logger logger

    def JpiDeveloper(Logger logger) {
        this.logger = logger
    }

    
    def getProperty(String f) {
        fields[f]
    }

    void setProperty(String f, val) {
        fields[f] = val
    }

    def methodMissing(String name, args) {
        if (legalFields.contains(name)) {
            setProperty(name, *args)
        } else { 
            logger.log(LogLevel.WARN, "JPI POM developer field ${name} not implemented.")
        }
    }
    
    def configure(Closure closure) {
        ConfigureUtil.configure(closure, this)
    }

}