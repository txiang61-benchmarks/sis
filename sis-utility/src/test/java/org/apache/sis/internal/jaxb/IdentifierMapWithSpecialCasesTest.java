/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sis.internal.jaxb;

import java.net.URI;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import org.opengis.metadata.Identifier;
import org.opengis.metadata.citation.Citation;
import org.apache.sis.xml.IdentifierMap;
import org.apache.sis.xml.IdentifierSpace;
import org.apache.sis.xml.XLink;
import org.apache.sis.test.DependsOn;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.apache.sis.test.TestUtilities.getSingleton;


/**
 * Tests {@link IdentifierMapWithSpecialCases}.
 *
 * @author  Martin Desruisseaux (Geomatys)
 * @since   0.3 (derived from geotk-3.19)
 * @version 0.3
 * @module
 */
@DependsOn(IdentifierMapAdapter.class)
public final strictfp class IdentifierMapWithSpecialCasesTest extends IdentifierMapAdapterTest {
    /**
     * The HREF string to replace by {@link XLink#toString()}.
     */
    private static final String TO_REPLACE = "xlink:href=“";

    /**
     * Creates the {@link IdentifierMapAdapter} instance to test for the given identifiers.
     *
     * @param  identifiers The identifiers to wrap in an {@code IdentifierMapAdapter}.
     * @return The {@code IdentifierMapAdapter} to test.
     */
    @Override
    IdentifierMapAdapter create(final Collection<Identifier> identifiers) {
        return new IdentifierMapWithSpecialCases(identifiers);
    }

    /**
     * Replaces the {@code xlink:href} value by the {@link XLink#toString()} value
     * before to compare with the map content.
     */
    @Override
    void assertMapEquals(String expected, final Map<Citation,String> map) {
        final int start = expected.indexOf(TO_REPLACE);
        if (start >= 0) {
            final int end = start + TO_REPLACE.length();
            final int close = expected.indexOf('”', end);
            final StringBuilder buffer = new StringBuilder(expected);
            buffer.replace(close, close+1, "\"]");
            buffer.replace(start, end, "xlink=XLink[href=\"");
            expected = buffer.toString();
        }
        super.assertMapEquals(expected, map);
    }

    /**
     * Wraps the given {@code href} value in a {@link XLink} string representation.
     */
    @Override
    String toHRefString(final String href) {
        return "XLink[href=\"" + href + "\"]";
    }

    // Inherits all test methods from the super class.

    /**
     * Tests explicitely the special handling of {@code href} values.
     */
    @Test
    public void testSpecialCases() {
        final List<Identifier> identifiers = new ArrayList<>();
        final IdentifierMap map = create(identifiers);
        map.put(IdentifierSpace.HREF, "myHREF");
        assertEquals("myHREF", map.get(IdentifierSpace.HREF));

        // Check the XLink object
        final XLink link = map.getSpecialized(IdentifierSpace.XLINK);
        assertEquals("myHREF", String.valueOf(link.getHRef()));
        assertEquals(link.toString(), getSingleton(identifiers).getCode());

        // Modidfy the XLink object directly
        link.setHRef(URI.create("myNewHREF"));
        assertEquals("myNewHREF", map.get(IdentifierSpace.HREF));
    }
}
