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

package org.xwiki.contrib.bookversions.internal;

import org.xwiki.contrib.bookversions.VersionedContent;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Book Page.
 *
 * @version $Id$
 * @since 0.1
 */
public class DefaultVersionedContent implements VersionedContent
{
    private XWikiDocument document;

    private BaseObject object;

    private String status;

    private boolean defined;

    /**
     * Constructor.
     * 
     * @param document The document storing the object.
     */
    public DefaultVersionedContent(XWikiDocument document)
    {
        this.document = document;
        this.object = this.document != null
            ? document.getXObject(BookVersionsConstants.BOOKVERSIONEDCONTENT_CLASS_REFERENCE) : null;
        this.defined = this.object != null;
        BaseObject statusObject = document.getXObject(BookVersionsConstants.PAGESTATUS_CLASS_REFERENCE);
        this.status =
            statusObject != null ? statusObject.getStringValue(BookVersionsConstants.PAGESTATUS_PROP_STATUS) : null;
    }

    @Override
    public XWikiDocument getDocument()
    {
        return this.document;
    }

    @Override
    public boolean isDefined()
    {
        return this.defined;
    }

    @Override
    public String getStatus()
    {
        return this.status;
    }

    @Override
    public boolean isDraft()
    {
        return this.status != null && this.status == BookVersionsConstants.PAGESTATUS_PROP_STATUS_DRAFT;
    }

    @Override
    public boolean isInReview()
    {
        return this.status != null && this.status == BookVersionsConstants.PAGESTATUS_PROP_STATUS_REVIEW;
    }

    @Override
    public boolean isComplete()
    {
        return this.status != null && this.status == BookVersionsConstants.PAGESTATUS_PROP_STATUS_COMPLETE;
    }
}
