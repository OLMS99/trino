/*
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
package io.trino.plugin.base.security;

import com.google.common.collect.ImmutableSet;
import io.trino.spi.security.Identity;
import io.trino.spi.security.SystemAccessControl;
import io.trino.spi.security.SystemSecurityContext;
import org.testng.annotations.Test;

import java.util.Collection;

import static io.trino.spi.testing.InterfaceTestUtils.assertAllMethodsOverridden;

public class TestForwardingSystemAccessControl
{
    @Test
    public void testEverythingDelegated()
            throws ReflectiveOperationException
    {
        assertAllMethodsOverridden(SystemAccessControl.class, ForwardingSystemAccessControl.class, ImmutableSet.of(
                ForwardingSystemAccessControl.class.getMethod("checkCanViewQueryOwnedBy", SystemSecurityContext.class, Identity.class),
                ForwardingSystemAccessControl.class.getMethod("filterViewQuery", SystemSecurityContext.class, Collection.class),
                ForwardingSystemAccessControl.class.getMethod("checkCanKillQueryOwnedBy", SystemSecurityContext.class, Identity.class)));
    }
}
