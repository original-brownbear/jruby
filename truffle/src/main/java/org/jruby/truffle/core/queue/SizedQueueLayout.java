/*
 * Copyright (c) 2015, 2016 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.core.queue;

import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.DynamicObjectFactory;
import org.jruby.truffle.core.basicobject.BasicObjectLayout;
import org.jruby.truffle.om.dsl.api.Layout;
import org.jruby.truffle.om.dsl.api.Nullable;

@Layout
public interface SizedQueueLayout extends BasicObjectLayout {

    DynamicObjectFactory createSizedQueueShape(DynamicObject logicalClass,
                                               DynamicObject metaClass);

    DynamicObject createSizedQueue(DynamicObjectFactory factory,
                                   @Nullable ArrayBlockingQueueLocksConditions<Object> queue);

    ArrayBlockingQueueLocksConditions<Object> getQueue(DynamicObject object);
    void setQueue(DynamicObject object, ArrayBlockingQueueLocksConditions<Object> queue);

}
