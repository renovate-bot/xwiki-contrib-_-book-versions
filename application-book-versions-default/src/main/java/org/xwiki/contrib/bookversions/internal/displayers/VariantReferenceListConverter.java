/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package  org.xwiki.contrib.bookversions.internal.displayers;

import java.lang.reflect.Type;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.inject.Named;

import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.properties.converter.AbstractConverter;
import org.xwiki.properties.converter.ConversionException;
import org.xwiki.text.StringUtils;

/**
 * XWiki Properties Bean Converter to convert Strings into {@link GroupReferenceList}.
 *
 * @version $Id$
 * @see org.xwiki.properties.converter.Converter
 */
@Component
@Singleton
public class VariantReferenceListConverter extends AbstractConverter<VariantReferenceList>
{
    @Inject
    @Named("current")
    private DocumentReferenceResolver<String> referenceResolver;

    @Override
    protected <G extends VariantReferenceList> G convertToType(Type targetType, Object value)
    {
        G references = (G) new VariantReferenceList();
        if (value != null) {
            String valueString = value.toString().trim();
            if (!StringUtils.isEmpty(valueString)) {
                String[] groupNames = valueString.split(",");
                for (String groupName : groupNames) {
                    String trimmedGroupName = groupName.trim();
                    if (StringUtils.isNotEmpty(trimmedGroupName)) {
                        references.add(referenceResolver.resolve(trimmedGroupName));
                    }
                }
            }
        }

        return references;
    }

    @Override
    protected String convertToString(VariantReferenceList value)
    {
        throw new ConversionException("not implemented yet");
    }
}
