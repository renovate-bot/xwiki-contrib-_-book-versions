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

package org.xwiki.contrib.bookversions.listeners;

import java.util.List;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.bridge.event.DocumentCreatingEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.bookversions.BookVersionsManager;
import org.xwiki.contrib.bookversions.internal.BookVersionsConstants;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.observation.event.AbstractLocalEventListener;
import org.xwiki.observation.event.Event;
import org.xwiki.query.QueryException;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

/**
 * Creating the versioned content page when a new versioned page is created in a book. Using the active version (the
 * version stored in session, then fallback on the most recent version.
 * 
 * @version $Id$
 * @since 0.1
 */
@Component
@Named(DocumentCreatingEventListener.NAME)
@Singleton
public class DocumentCreatingEventListener extends AbstractLocalEventListener
{

    static final String NAME = "org.xwiki.contrib.bookversions.listeners.DocumentUpdatingEventListener";

    private static final List<Event> EVENT_LIST = List.of(new DocumentCreatingEvent());

    @Inject
    private Provider<BookVersionsManager> bookVersionsManagerProvider;

    @Inject
    private Provider<XWikiContext> xcontextProvider;

    @Inject
    private Logger logger;

    /**
     * Constructor.
     */
    public DocumentCreatingEventListener()
    {
        super(NAME, EVENT_LIST);
    }

    @Override
    public void processLocalEvent(Event event, Object source, Object data)
    {
        XWikiContext xcontext = xcontextProvider.get();

        XWikiDocument updatedXDoc = (XWikiDocument) source;
        updatedXDoc.getOriginalDocument();
        BookVersionsManager bookVersionsManager = bookVersionsManagerProvider.get();

        try {
            if (bookVersionsManager.isPage(updatedXDoc) && bookVersionsManager.isVersionedPage(updatedXDoc)) {
                // Transfer the document's content in a new child page storing its versioned content.
                DocumentReference versionDocumentReference =
                    bookVersionsManager.getVersionedContentReference(updatedXDoc);

                if (versionDocumentReference != null) {
                    XWikiDocument versionedContentDocument =
                        xcontext.getWiki().getDocument(versionDocumentReference, xcontext);
                    versionedContentDocument.newXObject(BookVersionsConstants.BOOKVERSIONEDCONTENT_CLASS_REFERENCE,
                        xcontext);
                    versionedContentDocument.setContent(updatedXDoc.getContent());
                    DocumentReference userReference = this.xcontextProvider.get().getUserReference();
                    versionedContentDocument.setCreatorReference(userReference);
                    versionedContentDocument.setAuthorReference(userReference);
                    updatedXDoc.setContent("");

                    xcontext.getWiki().saveDocument(versionedContentDocument, xcontext);
                }
            }
        } catch (XWikiException | QueryException e) {
            logger.error("Could not handle the event listener.", e);
        }
    }
}
