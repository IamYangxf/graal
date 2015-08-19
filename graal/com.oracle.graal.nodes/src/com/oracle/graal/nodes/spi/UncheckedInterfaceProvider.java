/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes.spi;

import jdk.internal.jvmci.meta.*;

import com.oracle.graal.compiler.common.type.*;

public interface UncheckedInterfaceProvider {
    /**
     * Returns a stamp containing information about interface types that has not been verified or
     * null if no such stamp is available. A type check is needed before using informations from
     * this stamp.
     */
    Stamp uncheckedStamp();

    static Stamp uncheckedOrNull(JavaType type, Stamp originalStamp) {
        if (type instanceof ResolvedJavaType && type.getKind() == Kind.Object) {
            Stamp unchecked = StampFactory.declaredTrusted((ResolvedJavaType) type);
            if (!unchecked.equals(originalStamp)) {
                return unchecked;
            }
        }
        return null;
    }
}