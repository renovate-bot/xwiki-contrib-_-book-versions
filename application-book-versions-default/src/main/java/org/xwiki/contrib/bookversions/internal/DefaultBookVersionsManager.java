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

import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.servlet.http.HttpSession;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.contrib.bookversions.BookVersionsManager;
import org.xwiki.contrib.bookversions.PageTranslationStatus;
import org.xwiki.contrib.bookversions.internal.batch.LiveDataBatchRequest;
import org.xwiki.job.DefaultRequest;
import org.xwiki.job.JobException;
import org.xwiki.job.JobExecutor;
import org.xwiki.job.event.status.JobProgressManager;
import org.xwiki.livedata.LiveDataConfiguration;
import org.xwiki.localization.LocalizationManager;
import org.xwiki.logging.LogLevel;
import org.xwiki.logging.event.LogEvent;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.LocalDocumentReference;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.model.reference.SpaceReferenceResolver;
import org.xwiki.model.validation.EntityNameValidation;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;
import org.xwiki.rendering.block.Block;
import org.xwiki.rendering.block.MacroBlock;
import org.xwiki.rendering.block.XDOM;
import org.xwiki.rendering.block.match.ClassBlockMatcher;
import org.xwiki.rendering.macro.Macro;
import org.xwiki.rendering.macro.descriptor.ContentDescriptor;
import org.xwiki.rendering.parser.ParseException;
import org.xwiki.rendering.parser.Parser;
import org.xwiki.rendering.renderer.BlockRenderer;
import org.xwiki.rendering.renderer.printer.DefaultWikiPrinter;
import org.xwiki.rendering.renderer.printer.WikiPrinter;
import org.xwiki.user.UserReference;
import org.xwiki.user.UserReferenceResolver;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.api.Document;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.doc.merge.MergeConfiguration;
import com.xpn.xwiki.doc.merge.MergeResult;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.web.XWikiRequest;

/**
 * Default implementation of {@link BookVersionsManager}.
 *
 * @version $Id$
 * @since 0.1
 */
@Component
@Singleton
public class DefaultBookVersionsManager implements BookVersionsManager
{
    private static final ClassBlockMatcher MACRO_MATCHER = new ClassBlockMatcher(MacroBlock.class);

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Provider<QueryManager> queryManagerProvider;

    @Inject
    private DocumentReferenceResolver<String> referenceResolver;

    @Inject
    private DocumentReferenceResolver<EntityReference> referenceEntityResolver;

    @Inject
    private SpaceReferenceResolver<String> spaceReferenceResolver;

    @Inject
    @Named("currentmixed")
    private DocumentReferenceResolver<String> currentMixedReferenceResolver;

    @Inject
    @Named("explicit")
    private DocumentReferenceResolver<String> currentExplicitReferenceResolver;

    @Inject
    @Named("document")
    private UserReferenceResolver<DocumentReference> userReferenceResolver;

    @Inject
    @Named("local")
    private EntityReferenceSerializer<String> localSerializer;

    @Inject
    private EntityReferenceResolver<String> entityReferenceResolver;

    @Inject
    @Named("SlugEntityNameValidation")
    private Provider<EntityNameValidation> slugEntityNameValidationProvider;

    @Inject
    private Logger logger;

    @Inject
    private JobExecutor jobExecutor;

    @Inject
    private JobProgressManager progressManager;

    @Inject
    @Named("context")
    private Provider<ComponentManager> componentManagerProvider;

    @Inject
    private BookPublicationReferencesTransformationHelper publicationReferencesTransformationHelper;

    @Inject
    private LocalizationManager localization;

    @Override
    public boolean isBook(DocumentReference documentReference) throws XWikiException
    {
        XWikiContext xcontext = this.getXWikiContext();

        return isBook(this.getXWikiContext().getWiki().getDocument(documentReference, xcontext));
    }

    @Override
    public boolean isBook(XWikiDocument document) throws XWikiException
    {
        return new DefaultBook(document).isDefined();
    }

    @Override
    public boolean isPage(DocumentReference documentReference) throws XWikiException
    {
        XWikiContext xcontext = this.getXWikiContext();

        return isPage(xcontext.getWiki().getDocument(documentReference, xcontext));
    }

    @Override
    public boolean isPage(XWikiDocument document) throws XWikiException
    {
        return new DefaultPage(document).isDefined();
    }

    @Override
    public boolean isVersionedPage(DocumentReference documentReference) throws XWikiException
    {
        XWikiContext xcontext = this.getXWikiContext();

        return isVersionedPage(xcontext.getWiki().getDocument(documentReference, xcontext));
    }

    @Override
    public boolean isVersionedPage(XWikiDocument document) throws XWikiException
    {
        return new DefaultPage(document).isVersioned();
    }

    @Override
    public boolean isVersionedContent(DocumentReference documentReference) throws XWikiException
    {
        XWikiContext xcontext = this.getXWikiContext();

        return isVersionedContent(xcontext.getWiki().getDocument(documentReference, xcontext));
    }

    @Override
    public boolean isVersionedContent(XWikiDocument document) throws XWikiException
    {
        return new DefaultVersionedContent(document).isDefined();
    }

    @Override
    public boolean isPossibleVersionedContentReference(DocumentReference collectionReference,
        DocumentReference documentReference) throws XWikiException
    {
        return getVersionReference(collectionReference, documentReference.getName()) != null;
    }

    @Override
    public boolean isVersion(DocumentReference documentReference) throws XWikiException
    {
        XWikiContext xcontext = this.getXWikiContext();

        return isVersion(xcontext.getWiki().getDocument(documentReference, xcontext));
    }

    @Override
    public boolean isVersion(XWikiDocument document) throws XWikiException
    {
        return new DefaultVersion(document).isDefined();
    }

    @Override
    public boolean isVariant(DocumentReference documentReference) throws XWikiException
    {
        XWikiContext xcontext = this.getXWikiContext();

        return isVariant(xcontext.getWiki().getDocument(documentReference, xcontext));
    }

    private boolean isVariant(XWikiDocument document) throws XWikiException
    {
        return new DefaultVariant(document).isDefined();
    }

    @Override
    public boolean isLibrary(DocumentReference documentReference) throws XWikiException
    {
        XWikiContext xcontext = this.getXWikiContext();

        return isLibrary(xcontext.getWiki().getDocument(documentReference, xcontext));
    }

    private boolean isLibrary(XWikiDocument document) throws XWikiException
    {
        return new DefaultLibrary(document).isDefined();
    }

    @Override
    public boolean isFromLibrary(DocumentReference libraryReference, DocumentReference libraryVersionReference)
        throws QueryException, XWikiException
    {
        for (String versionStringRef : getCollectionVersions(libraryReference)) {
            if (versionStringRef != null && libraryVersionReference != null && libraryVersionReference
                .equals(referenceResolver.resolve(versionStringRef, libraryVersionReference)))
            {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean isFromLibrary(XWikiDocument library, XWikiDocument libraryVersion)
        throws QueryException, XWikiException
    {
        return library != null && libraryVersion != null
            ? isFromLibrary(library.getDocumentReference(), libraryVersion.getDocumentReference()) : false;
    }

    @Override
    public boolean isMarkedDeleted(DocumentReference documentReference) throws XWikiException, QueryException
    {
        String queryString = "select doc.fullName from XWikiDocument as doc, BaseObject as obj where obj.name = doc.fullName "
            + "and obj.className = :className and doc.fullName = :docFullName";
        List<String> results = this.queryManagerProvider.get()
            .createQuery(queryString, Query.HQL)
            .bindValue("className", localSerializer.serialize(BookVersionsConstants.MARKEDDELETED_CLASS_REFERENCE))
            .bindValue("docFullName", localSerializer.serialize(documentReference))
            .setWiki(documentReference.getWikiReference().getName())
            .execute();
        return results != null && results.size() > 0;
    }

    @Override
    public String transformUsingSlugValidation(String name)
    {
        EntityNameValidation modelValidationScriptService = this.slugEntityNameValidationProvider.get();

        return name != null ? modelValidationScriptService.transform(name) : null;
    }

    @Override
    public String getSelectedVersion(DocumentReference documentReference) throws XWikiException, QueryException
    {
        if (documentReference == null) {
            return null;
        }

        DocumentReference versionedCollectionReference = getVersionedCollectionReference(documentReference);
        Map<String, String> versionsMap = new HashMap<String, String>();
        XWikiRequest request = getXWikiContext().getRequest();
        HttpSession session = request.getSession();
        if (session != null) {
            versionsMap = (Map<String, String>) session.getAttribute(BookVersionsConstants.SESSION_SELECTEDVERSION);

            if (versionsMap != null) {
                Iterator<?> it = versionsMap.entrySet().iterator();

                while (it.hasNext()) {
                    Map.Entry<String, String> collectionVersion = (Map.Entry<String, String>) it.next();
                    String collectionReference = collectionVersion.getKey();
                    if (collectionReference != null && !collectionReference.isBlank()
                        && collectionReference.equals(localSerializer.serialize(versionedCollectionReference)))
                    {
                        return collectionVersion.getValue();
                    }
                }
            }
        }

        List<String> collectionVersions = getCollectionVersions(documentReference);
        if (collectionVersions != null && collectionVersions.size() > 0) {
            return collectionVersions.get(0);
        }

        return null;
    }

    @Override
    public void setSelectedVersion(DocumentReference documentReference, String version)
    {
        if (documentReference == null) {
            return;
        }

        Map<String, String> versionsMap = new HashMap<String, String>();
        XWikiRequest request = getXWikiContext().getRequest();
        HttpSession session = request.getSession();
        if (session != null) {
            Object sessionAttribute = session.getAttribute(BookVersionsConstants.SESSION_SELECTEDVERSION);
            versionsMap = sessionAttribute != null ? (Map<String, String>) sessionAttribute : versionsMap;
            String collectionReferenceSerialized = localSerializer.serialize(documentReference);
            versionsMap.put(collectionReferenceSerialized, version);
            session.setAttribute(BookVersionsConstants.SESSION_SELECTEDVERSION, versionsMap);
        }
    }

    @Override
    public String getSelectedVariant(DocumentReference documentReference) throws XWikiException, QueryException
    {
        if (documentReference == null) {
            return null;
        }

        Map<String, String> variantsMap = new HashMap<String, String>();
        XWikiRequest request = getXWikiContext().getRequest();
        HttpSession session = request.getSession();
        if (session != null) {
            variantsMap = (Map<String, String>) session.getAttribute(BookVersionsConstants.SESSION_SELECTEDVARIANT);

            if (variantsMap != null) {
                Iterator<?> it = variantsMap.entrySet().iterator();
                DocumentReference versionedCollectionReference = getVersionedCollectionReference(documentReference);

                while (it.hasNext()) {
                    Map.Entry<String, String> collectionVariant = (Map.Entry<String, String>) it.next();
                    String collectionReference = collectionVariant.getKey();
                    if (collectionReference != null && !collectionReference.isBlank()
                        && collectionReference.equals(localSerializer.serialize(versionedCollectionReference)))
                    {
                        return collectionVariant.getValue();
                    }
                }
            }
        }

        return null;
    }

    @Override
    public List<DocumentReference> getPageVariants(DocumentReference pageReference)
    {
        // ATTENTION
        // This storage needs improvements
        // It's a DB List property with NO RELATIONAL STORAGE !
        // It should be transformed into relational storage and then all values should be migrated to the new format
        List<DocumentReference> result = new ArrayList<DocumentReference>();
        if (pageReference == null) {
            return result;
        }

        String variantQueryString =
            "select distinct prop.value from BaseObject as obj, LargeStringProperty as prop where "
                + "obj.className = :className and obj.name = :objectName and prop.id.id = obj.id and prop.name = :propName";
        try {
            List<String> variantsList = this.queryManagerProvider.get().createQuery(variantQueryString, Query.HQL)
                .bindValue("className", localSerializer.serialize(BookVersionsConstants.VARIANTLIST_CLASS_REFERENCE))
                .bindValue("objectName", localSerializer.serialize(pageReference))
                .bindValue("propName", BookVersionsConstants.VARIANTLIST_PROP_VARIANTSLIST)
                .setWiki(pageReference.getWikiReference().getName()).execute();
            if (variantsList.size() > 0) {
                for (String variant : variantsList.get(0).split("\\|")) {
                    result.add(referenceResolver.resolve(variant).setWikiReference(pageReference.getWikiReference()));
                }
            }
        } catch (QueryException e) {
            logger.error("Could not compute the list of variants for page [{}] : [{}]", pageReference, e);
        }

        return result;
    }

    @Override
    public void setSelectedVariant(DocumentReference documentReference, String variant)
    {
        if (documentReference == null) {
            return;
        }

        Map<String, String> variantsMap = new HashMap<String, String>();
        XWikiRequest request = getXWikiContext().getRequest();
        HttpSession session = request.getSession();
        if (session != null) {
            Object sessionAttribute = session.getAttribute(BookVersionsConstants.SESSION_SELECTEDVARIANT);
            variantsMap = sessionAttribute != null ? (Map<String, String>) sessionAttribute : variantsMap;
            String collectionReferenceSerialized = localSerializer.serialize(documentReference);
            variantsMap.put(collectionReferenceSerialized, variant);
            session.setAttribute(BookVersionsConstants.SESSION_SELECTEDVARIANT, variantsMap);
        }
    }

    @Override
    public String getSelectedLanguage(DocumentReference documentReference) throws XWikiException, QueryException
    {
        if (documentReference == null) {
            return null;
        }

        Map<String, String> languagesMap = new HashMap<String, String>();
        XWikiRequest request = getXWikiContext().getRequest();
        HttpSession session = request.getSession();
        if (session != null) {
            languagesMap = (Map<String, String>) session.getAttribute(BookVersionsConstants.SESSION_SELECTEDLANGUAGE);

            if (languagesMap != null) {
                Iterator<?> it = languagesMap.entrySet().iterator();
                DocumentReference versionedCollectionReference = getVersionedCollectionReference(documentReference);

                while (it.hasNext()) {
                    Map.Entry<String, String> collectionLanguage = (Map.Entry<String, String>) it.next();
                    String collectionReference = collectionLanguage.getKey();
                    if (collectionReference != null && !collectionReference.isBlank()
                        && collectionReference.equals(localSerializer.serialize(versionedCollectionReference)))
                    {
                        return collectionLanguage.getValue();
                    }
                }
            }
        }

        return null;
    }

    private String getSelectedLanguage(Document document) throws XWikiException, QueryException
    {
        if (document == null) {
            return null;
        }

        Map<String, String> languagesMap = new HashMap<String, String>();
        XWikiRequest request = getXWikiContext().getRequest();
        HttpSession session = request.getSession();
        if (session != null) {
            languagesMap = (Map<String, String>) session.getAttribute(BookVersionsConstants.SESSION_SELECTEDLANGUAGE);

            if (languagesMap != null) {
                Iterator<?> it = languagesMap.entrySet().iterator();
                DocumentReference versionedCollectionReference = getVersionedCollectionReference(document);

                while (it.hasNext()) {
                    Map.Entry<String, String> collectionLanguage = (Map.Entry<String, String>) it.next();
                    String collectionReference = collectionLanguage.getKey();
                    if (collectionReference != null && !collectionReference.isBlank()
                        && collectionReference.equals(localSerializer.serialize(versionedCollectionReference)))
                    {
                        return collectionLanguage.getValue();
                    }
                }
            }
        }

        return null;
    }

    @Override
    public void setSelectedLanguage(DocumentReference documentReference, String language)
    {
        if (documentReference == null) {
            return;
        }

        Map<String, String> languagesMap = new HashMap<String, String>();
        XWikiRequest request = getXWikiContext().getRequest();
        HttpSession session = request.getSession();
        if (session != null) {
            Object sessionAttribute = session.getAttribute(BookVersionsConstants.SESSION_SELECTEDLANGUAGE);
            languagesMap = sessionAttribute != null ? (Map<String, String>) sessionAttribute : languagesMap;
            String collectionReferenceSerialized = localSerializer.serialize(documentReference);
            languagesMap.put(collectionReferenceSerialized, language);
            session.setAttribute(BookVersionsConstants.SESSION_SELECTEDLANGUAGE, languagesMap);
        }
    }

    @Override
    public String getDefaultTranslation(DocumentReference documentReference) throws XWikiException, QueryException
    {
        Map<String, Map<String, Object>> languageData = getLanguageData(documentReference);

        for (Entry<String, Map<String, Object>> languageDataEntry : languageData.entrySet()) {
            if (languageDataEntry != null && languageDataEntry.getValue() != null
                && (boolean) languageDataEntry.getValue().get(BookVersionsConstants.PAGETRANSLATION_ISDEFAULT))
            {
                return (String) languageDataEntry.getKey();
            }
        }

        // There is no default language set for the current document.
        // Use the default languages in the Preferences.
        return getXWikisDefaultLanguage();
    }

    private String getDefaultTranslation(Document document) throws XWikiException, QueryException
    {
        Map<String, Map<String, Object>> languageData = getLanguageData(document);

        for (Entry<String, Map<String, Object>> languageDataEntry : languageData.entrySet()) {
            if (languageDataEntry != null && languageDataEntry.getValue() != null
                && (boolean) languageDataEntry.getValue().get(BookVersionsConstants.PAGETRANSLATION_ISDEFAULT))
            {
                return (String) languageDataEntry.getKey();
            }
        }

        // There is no default language set for the current document.
        // Use the default languages in the Preferences.
        return getXWikisDefaultLanguage();
    }

    @Override
    public String getTranslatedTitle(Document document) throws XWikiException, QueryException
    {
        String selectedLanguage = getSelectedLanguage(document);

        if (selectedLanguage == null || selectedLanguage.isEmpty()) {
            selectedLanguage = this.getDefaultTranslation(document);
        }

        return selectedLanguage != null ? getTranslatedTitle(document, selectedLanguage)
            : document.getDocumentReference().getName();
    }

    @Override
    public String getTranslatedTitle(DocumentReference documentReference) throws XWikiException, QueryException
    {
        DocumentReference collectionRef = getVersionedCollectionReference(documentReference);
        String selectedLanguage = getSelectedLanguage(collectionRef);

        if (selectedLanguage == null) {
            selectedLanguage = this.getDefaultTranslation(documentReference);
        }

        return selectedLanguage != null ? getTranslatedTitle(documentReference, selectedLanguage)
            : documentReference.getName();
    }

    @Override
    public String getTranslatedTitle(DocumentReference documentReference, String language)
        throws XWikiException, QueryException
    {
        XWikiContext xcontext = this.getXWikiContext();

        return getTranslatedTitle(xcontext.getWiki().getDocument(documentReference, xcontext), language);
    }

    @Override
    public String getTranslatedTitle(XWikiDocument document, String language) throws XWikiException, QueryException
    {
        if (document == null) {
            return null;
        }

        String title = null;
        for (BaseObject tObj : document.getXObjects(BookVersionsConstants.PAGETRANSLATION_CLASS_REFERENCE)) {
            if (tObj != null) {
                String languageEntry = tObj.getStringValue(BookVersionsConstants.PAGETRANSLATION_LANGUAGE);
                if (languageEntry != null && !languageEntry.isEmpty() && languageEntry.equals(language)) {
                    title = tObj.getStringValue(BookVersionsConstants.PAGETRANSLATION_TITLE);
                }
            }
        }

        return title != null && !title.isEmpty() ? title : getInheritedTitle(document);
    }

    private String getTranslatedTitle(Document document, String language) throws XWikiException, QueryException
    {
        if (document == null || language == null || language.isEmpty()) {
            return null;
        }

        String title = null;
        com.xpn.xwiki.api.Object tObj = document.getObject(BookVersionsConstants.PAGE_TRANSLATION_CLASS_SERIALIZED,
            BookVersionsConstants.PAGETRANSLATION_LANGUAGE, language);
        if (tObj != null) {
            title = (String) tObj.getValue(BookVersionsConstants.PAGETRANSLATION_TITLE);
        }

        return title != null && !title.isEmpty() ? title : getInheritedTitle(document);
    }

    private String getInheritedTitle(Document document) throws XWikiException
    {
        if (document.getObject(BookVersionsConstants.BOOKVERSIONEDCONTENT_CLASS_SERIALIZED) != null) {

            DocumentReference documentReference = document.getDocumentReference();
            SpaceReference parentSpaceReference = getSpaceReference(documentReference);
            if (parentSpaceReference != null) {
                DocumentReference parentPageReference = new DocumentReference("WebHome", parentSpaceReference);
                // The parent is a book page, so use its title.
                Document parentDocument = new Document(new XWikiDocument(parentPageReference), getXWikiContext());
                if (parentDocument.getObject(BookVersionsConstants.BOOKPAGE_CLASS_SERIALIZED) != null) {
                    return parentDocument.getTitle();
                }
            }
        }

        return document != null ? document.getDocumentReference().getName() : null;
    }

    private String getInheritedTitle(XWikiDocument document) throws XWikiException
    {
        if (isVersionedContent(document)) {

            DocumentReference documentReference = document.getDocumentReference();
            SpaceReference parentSpaceReference = getSpaceReference(documentReference);
            if (parentSpaceReference != null) {
                XWikiContext xcontext = this.getXWikiContext();

                DocumentReference parentPageReference =
                    new DocumentReference(xcontext.getWiki().DEFAULT_SPACE_HOMEPAGE, parentSpaceReference);
                // The parent is a book page, so use its title.
                if (isPage(parentPageReference)) {
                    return xcontext.getWiki().getDocument(parentPageReference, xcontext).getTitle();
                }
            }
        }

        return document != null ? document.getDocumentReference().getName() : null;
    }

    @Override
    public String getTranslationStatus(DocumentReference documentReference, String language)
        throws XWikiException, QueryException
    {
        XWikiContext xcontext = this.getXWikiContext();

        return getTranslationStatus(xcontext.getWiki().getDocument(documentReference, xcontext), language);
    }

    @Override
    public String getTranslationStatus(XWikiDocument document) throws XWikiException, QueryException
    {
        if (document == null) {
            return null;
        }

        DocumentReference collectionRef = getVersionedCollectionReference(document.getDocumentReference());
        String selectedLanguage = getSelectedLanguage(collectionRef);

        BaseObject translationObject = null;
        for (BaseObject tObj : document.getXObjects(BookVersionsConstants.PAGETRANSLATION_CLASS_REFERENCE)) {
            if (tObj == null) {
                continue;
            }
            String languageEntry = tObj.getStringValue(BookVersionsConstants.PAGETRANSLATION_LANGUAGE);
            if (languageEntry != null && !languageEntry.isEmpty() && selectedLanguage != null
                && languageEntry.equals(selectedLanguage))
            {
                translationObject = tObj;
                break;
            }
        }

        return translationObject != null
            ? translationObject.getStringValue(BookVersionsConstants.PAGETRANSLATION_STATUS) : null;
    }

    @Override
    public String getTranslationStatus(XWikiDocument document, String language) throws XWikiException, QueryException
    {
        if (document == null) {
            return null;
        }

        for (BaseObject tObj : document.getXObjects(BookVersionsConstants.PAGETRANSLATION_CLASS_REFERENCE)) {
            if (tObj == null) {
                continue;
            }
            String languageEntry = tObj.getStringValue(BookVersionsConstants.PAGETRANSLATION_LANGUAGE);
            if (languageEntry != null && !languageEntry.isEmpty() && language != null
                && languageEntry.equals(language))
            {
                return tObj.getStringValue(BookVersionsConstants.PAGETRANSLATION_STATUS);
            }
        }

        return null;
    }

    @Override
    public boolean isDefaultLanguage(DocumentReference documentReference, String language)
        throws XWikiException, QueryException
    {
        XWikiContext xcontext = this.getXWikiContext();

        return isDefaultLanguage(xcontext.getWiki().getDocument(documentReference, xcontext), language);
    }

    @Override
    public boolean isDefaultLanguage(XWikiDocument document, String language) throws XWikiException, QueryException
    {
        for (BaseObject tObj : document.getXObjects(BookVersionsConstants.PAGETRANSLATION_CLASS_REFERENCE)) {
            if (tObj == null) {
                continue;
            }
            String languageEntry = tObj.getStringValue(BookVersionsConstants.PAGETRANSLATION_LANGUAGE);
            if (languageEntry != null && !languageEntry.isEmpty() && language != null
                && languageEntry.equals(language))
            {
                int isDefault = tObj.getIntValue(BookVersionsConstants.PAGETRANSLATION_ISDEFAULT);
                if (isDefault == 1) {
                    return true;
                }
                break;
            }
        }

        return false;
    }

    @Override
    public boolean isAParent(DocumentReference spaceReference, DocumentReference nestedReference)
    {
        if (spaceReference != null && nestedReference != null) {
            List<SpaceReference> childSpaces = nestedReference.getSpaceReferences();

            for (SpaceReference parentSpace : spaceReference.getSpaceReferences()) {

                if (childSpaces.contains(parentSpace)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Get the name of the page, but escaped for dot character.
     *
     * @param documentReference the document reference to get the name from
     * @return the escaped name of the page
     */
    private String getEscapedName(DocumentReference documentReference)
    {
        if (documentReference != null) {
            return getEscapedName(documentReference.getName());
        } else {
            return null;
        }
    }

    /**
     * Get the name of the page, but escaped for dot character.
     *
     * @param name the name to escape
     * @return the escaped name of the page
     */
    private String getEscapedName(String name)
    {
        if (name == null) {
            return null;
        }

        String sequence = "\\.";
        String escapedName = name.replaceAll(sequence, sequence);

        logger.debug("[getEscapedName] escapedName : [{}]", escapedName);

        return escapedName;
    }

    @Override
    public DocumentReference getVersionReference(DocumentReference collectionReference, String version)
        throws XWikiException
    {
        if (collectionReference == null || version == null) {
            return null;
        }
        // Search first for the terminal document : Book.Versions.MyVersion
        SpaceReference versionParentSpaceReference =
            new SpaceReference(new EntityReference(BookVersionsConstants.VERSIONS_LOCATION, EntityType.SPACE,
                collectionReference.getParent()));
        DocumentReference versionDocumentReference = versionParentSpaceReference != null
            ? new DocumentReference(new EntityReference(version, EntityType.DOCUMENT, versionParentSpaceReference))
            : null;

        // Search for the non-terminal document : Book.Versions.MyVersion.WebHome
        if (!this.isVersion(versionDocumentReference)) {
            SpaceReference versionNonTerminalParentSpaceReference =
                new SpaceReference(new EntityReference(version, EntityType.SPACE, versionParentSpaceReference));
            versionDocumentReference =
                new DocumentReference(new EntityReference(this.getXWikiContext().getWiki().DEFAULT_SPACE_HOMEPAGE,
                    EntityType.DOCUMENT, versionNonTerminalParentSpaceReference));
        }

        return this.isVersion(versionDocumentReference) ? versionDocumentReference : null;
    }

    @Override
    public DocumentReference getVariantReference(DocumentReference collectionReference, String variant)
        throws XWikiException
    {
        if (collectionReference == null || variant == null) {
            return null;
        }

        // Search first for the terminal document : Book.Versions.MyVersion
        SpaceReference variantParentSpaceReference =
            new SpaceReference(new EntityReference(BookVersionsConstants.VARIANTS_LOCATION, EntityType.SPACE,
                collectionReference.getParent()));
        DocumentReference variantDocumentReference =
            new DocumentReference(new EntityReference(variant, EntityType.DOCUMENT, variantParentSpaceReference));

        // Search for the non-terminal document : Book.Versions.MyVersion.WebHome
        if (variantParentSpaceReference != null && !this.isVariant(variantDocumentReference)) {
            SpaceReference versionNonTerminalParentSpaceReference =
                new SpaceReference(new EntityReference(variant, EntityType.SPACE, variantParentSpaceReference));
            variantDocumentReference =
                new DocumentReference(new EntityReference(this.getXWikiContext().getWiki().DEFAULT_SPACE_HOMEPAGE,
                    EntityType.DOCUMENT, versionNonTerminalParentSpaceReference));
        }

        return isVariant(variantDocumentReference) ? variantDocumentReference : null;
    }

    @Override
    public List<String> getCollectionVersions(DocumentReference collectionReference)
        throws QueryException, XWikiException
    {
        if (collectionReference == null) {
            return Collections.emptyList();
        }

        DocumentReference versionedCollectionReference = getVersionedCollectionReference(collectionReference);

        return queryPages(versionedCollectionReference, BookVersionsConstants.VERSION_CLASS_REFERENCE);
    }

    @Override
    public List<String> getCollectionVariants(DocumentReference collectionReference)
        throws QueryException, XWikiException
    {
        if (collectionReference == null) {
            return Collections.emptyList();
        }

        DocumentReference versionedCollectionReference = getVersionedCollectionReference(collectionReference);

        return queryPages(versionedCollectionReference, BookVersionsConstants.VARIANT_CLASS_REFERENCE);
    }

    /**
     * Query pages under a given document, ordered by descending creation date
     *
     * @param documentReference the root document for the query
     * @return the list of pages under the document, ordered by descending creation date
     * @throws QueryException happens when the query creation or execution has an issue.
     */
    private List<String> queryPages(DocumentReference documentReference)
        throws QueryException
    {
        return queryPages(documentReference, null);
    }

    @Override
    public List<String> queryPages(DocumentReference documentReference, EntityReference classReference)
        throws QueryException
    {
        if (documentReference == null) {
            return Collections.emptyList();
        }

        if (classReference != null) {
            logger.debug("[queryPages] Query pages with class [{}] under [{}]", classReference, documentReference);
        } else {
            logger.debug("[queryPages] Query pages under [{}]", documentReference);
        }

        SpaceReference spaceReference = documentReference.getLastSpaceReference();
        String spaceSerialized = localSerializer.serialize(spaceReference);
        String spacePrefix = spaceSerialized.replaceAll("([%_/])", "/$1").concat(".%");

        logger.debug("[queryPages] spaceSerialized : [{}]", spaceSerialized);
        logger.debug("[queryPages] spacePrefix : [{}]", spacePrefix);

        // Query inspired from getDocumentReferences of DefaultModelBridge.java in xwiki-platform-refactoring
        String statement = "where doc.space like :space escape '/' order by doc.creationDate desc";
        if (classReference != null) {
            statement = ", BaseObject as obj where doc.fullName = obj.name and obj.className = :class "
                + "and doc.space like :space escape '/' order by doc.creationDate desc";
        }
        Query query = this.queryManagerProvider.get()
            .createQuery(statement, Query.HQL)
            .bindValue("space", spacePrefix)
            .setWiki(documentReference.getWikiReference().getName());
        if (classReference != null) {
            query = query.bindValue("class", localSerializer.serialize(classReference));
        }
        List<String> result = query.execute();

        logger.debug("[queryPages] result : [{}]", result);

        return result;
    }

    /**
     * Search for the parent storing the collection type (book or library).
     */
    @Override
    public DocumentReference getVersionedCollectionReference(Document document) throws XWikiException, QueryException
    {
        if (document == null) {
            return null;
        }

        DocumentReference documentReference = document.getDocumentReference();

        if (document.getObject(BookVersionsConstants.BOOK_CLASS_SERIALIZED) != null
            || document.getObject(BookVersionsConstants.LIBRARY_CLASS_SERIALIZED) != null
            || document.getObject(BookVersionsConstants.PUBLISHED_BOOK_CLASS_SERIALIZED) != null)
        {
            return documentReference;
        }

        EntityReference entityReference = documentReference.getParent();

        if (entityReference != null) {

            // Check if the parent is a document.
            EntityReference documentEntityReference = entityReference.extractReference(EntityType.DOCUMENT);
            if (documentEntityReference != null) {
                DocumentReference parentDocumentReference = documentEntityReference instanceof DocumentReference
                    ? (DocumentReference) documentEntityReference : new DocumentReference(documentEntityReference);

                // Verify recursively if the parent is storing the collection definition.
                return getVersionedCollectionReference(parentDocumentReference);
            } else {
                // Check if the parent is a space.
                SpaceReference parentSpaceReference = getSpaceReference(entityReference);

                // Check if the parent itself is a collection
                DocumentReference parentDocumentReference = null;
                if (parentSpaceReference != null) {
                    parentDocumentReference = new DocumentReference(
                        this.getXWikiContext().getWiki().DEFAULT_SPACE_HOMEPAGE, parentSpaceReference);
                    if (isBook(parentDocumentReference) || isLibrary(parentDocumentReference)) {
                        return parentDocumentReference;
                    }
                }

                // If the parent is a space, but it's the last space of the given reference,
                // then go upper with one level, to the parent of the parent to avoid Stack Overflow.
                if (parentSpaceReference != null
                    && parentSpaceReference.equals(documentReference.getLastSpaceReference()))
                {
                    parentSpaceReference = getSpaceReference(parentSpaceReference.getParent());
                }

                // Get the document reference of the root for the parent space.
                parentDocumentReference = parentSpaceReference != null ? new DocumentReference(
                    this.getXWikiContext().getWiki().DEFAULT_SPACE_HOMEPAGE, parentSpaceReference) : null;

                // Verify recursively if this document is storing the collection definition.
                return getVersionedCollectionReference(parentDocumentReference);
            }
        }

        return null;
    }

    /**
     * Search for the parent storing the collection type (book or library).
     */
    @Override
    public DocumentReference getVersionedCollectionReference(DocumentReference documentReference)
        throws XWikiException, QueryException
    {
        if (documentReference == null) {
            return null;
        }

        XWikiContext xcontext = getXWikiContext();

        if (isBook(documentReference) || isLibrary(documentReference)
            || xcontext.getWiki().getDocument(documentReference, xcontext)
            .getXObject(BookVersionsConstants.PUBLISHEDCOLLECTION_CLASS_REFERENCE) != null)
        {
            return documentReference;
        }

        EntityReference entityReference = documentReference.getParent();

        if (entityReference != null) {

            // Check if the parent is a document.
            EntityReference documentEntityReference = entityReference.extractReference(EntityType.DOCUMENT);
            if (documentEntityReference != null) {
                DocumentReference parentDocumentReference = documentEntityReference instanceof DocumentReference
                    ? (DocumentReference) documentEntityReference : new DocumentReference(documentEntityReference);

                // Verify recursively if the parent is storing the collection definition.
                return getVersionedCollectionReference(parentDocumentReference);
            } else {
                // Check if the parent is a space.
                SpaceReference parentSpaceReference = getSpaceReference(entityReference);

                // Check if the parent itself is a collection
                DocumentReference parentDocumentReference = null;
                if (parentSpaceReference != null) {
                    parentDocumentReference = new DocumentReference(
                        this.getXWikiContext().getWiki().DEFAULT_SPACE_HOMEPAGE, parentSpaceReference);
                    if (isBook(parentDocumentReference) || isLibrary(parentDocumentReference)) {
                        return parentDocumentReference;
                    }
                }

                // If the parent is a space, but it's the last space of the given reference,
                // then go upper with one level, to the parent of the parent to avoid Stack Overflow.
                if (parentSpaceReference != null
                    && parentSpaceReference.equals(documentReference.getLastSpaceReference()))
                {
                    parentSpaceReference = getSpaceReference(parentSpaceReference.getParent());
                }

                // Get the document reference of the root for the parent space.
                parentDocumentReference = parentSpaceReference != null ? new DocumentReference(
                    this.getXWikiContext().getWiki().DEFAULT_SPACE_HOMEPAGE, parentSpaceReference) : null;

                // Verify recursively if this document is storing the collection definition.
                return getVersionedCollectionReference(parentDocumentReference);
            }
        }

        return null;
    }

    @Override
    public boolean hasContentForVersion(DocumentReference documentReference, String version)
        throws QueryException, XWikiException
    {
        if (version == null || version.isBlank() || documentReference == null) {
            return false;
        }

        XWikiContext xcontext = this.getXWikiContext();
        DocumentReference pageReference = documentReference;

        // If the reference is not a page, go to its parent, assuming that the current ref is a versioned content page.
        if (!isPage(documentReference)) {

            SpaceReference parentSpaceReference = getSpaceReference(documentReference);
            if (parentSpaceReference != null
                && parentSpaceReference.equals(documentReference.getLastSpaceReference()))
            {
                parentSpaceReference = getSpaceReference(parentSpaceReference.getParent());
            }
            if (parentSpaceReference != null) {
                pageReference = new DocumentReference(this.getXWikiContext().getWiki().DEFAULT_SPACE_HOMEPAGE,
                    parentSpaceReference);
            }

            // The parent is not a page either.
            if (!isPage(pageReference)) {
                return false;
            }
        }

        DocumentReference versionedContentReference =
            pageReference != null ? this.getVersionedContentReference(pageReference, version) : null;
        return versionedContentReference != null && xcontext.getWiki().exists(versionedContentReference, xcontext);
    }

    @Override
    public String getVersionName(DocumentReference versionReference)
    {
        String versionName = getEscapedName(versionReference);
        if (versionName != null && versionName.equals(this.getXWikiContext().getWiki().DEFAULT_SPACE_HOMEPAGE)) {
            versionName = getEscapedName(versionReference.getLastSpaceReference().getName());
        }

        return versionName;
    }

    @Override
    public String getVariantName(DocumentReference variantReference)
    {
        String variantName = getEscapedName(variantReference);
        if (variantName != null && variantName.equals(this.getXWikiContext().getWiki().DEFAULT_SPACE_HOMEPAGE)) {
            variantName = getEscapedName(variantReference.getLastSpaceReference().getName());
        }

        return variantName;
    }

    @Override
    public DocumentReference getVersionedContentReference(DocumentReference documentReference)
        throws XWikiException, QueryException
    {
        return isVersionedPage(documentReference)
            ? getVersionedContentReference(documentReference, getSelectedVersion(documentReference)) : null;
    }

    @Override
    public DocumentReference getVersionedContentReference(XWikiDocument document) throws XWikiException, QueryException
    {
        if (isVersionedPage(document)) {
            DocumentReference versionedCollectionReference =
                getVersionedCollectionReference(document.getDocumentReference());

            return versionedCollectionReference != null ? getVersionedContentReference(document.getDocumentReference(),
                getSelectedVersion(versionedCollectionReference)) : null;
        }

        return null;
    }

    @Override
    public DocumentReference getVersionedContentReference(DocumentReference documentReference, String version)
        throws QueryException, XWikiException
    {
        if (version != null && !version.isBlank() && documentReference != null) {
            DocumentReference versionDocumentReference =
                referenceResolver.resolve(version).setWikiReference(this.getXWikiContext().getWikiReference());
            return getVersionedContentReference(documentReference, versionDocumentReference);
        }

        List<String> collectionVersions = getCollectionVersions(documentReference);
        if (collectionVersions != null && collectionVersions.size() > 0) {
            return referenceResolver.resolve(collectionVersions.get(0))
                .setWikiReference(this.getXWikiContext().getWikiReference());
        }

        return null;
    }

    @Override
    public DocumentReference getVersionedContentReference(DocumentReference pageReference,
        DocumentReference versionReference)
    {
        String versionName = getVersionName(versionReference);

        logger.debug("[getVersionedContentReference] versionName : [{}]", versionName);

        return versionName != null
            ? new DocumentReference(new EntityReference(versionName, EntityType.DOCUMENT, pageReference.getParent()))
            : null;
    }

    /**
     * Get the list of preceding versions of a given version.
     * @param collectionReference the collection of the version
     * @param versionReference the version to get the preceding version from
     * @return the preceding versions of the given version, from the first previous to the root version. Empty list
     * if null parameter or infinite loop of versions is detected
     * @throws XWikiException
     * @throws QueryException
     */
    private List<DocumentReference> getVersionsAscending(DocumentReference collectionReference,
        DocumentReference versionReference)
        throws XWikiException, QueryException
    {
        List<DocumentReference> result = new ArrayList<>();
        if (collectionReference == null || versionReference == null || !isVersion(versionReference)) {
            return result;
        }

        int versionQuantity = getCollectionVersions(collectionReference).size();

        String versionName = getVersionName(versionReference);
        DocumentReference previousVersionReference = versionReference;
        int i = 0;
        result.add(versionReference);

        logger.debug("[getVersionsAscending] get versions before : [{}]", versionName);
        while (i < versionQuantity + 1) {
            previousVersionReference = getPreviousVersion(previousVersionReference);
            if (previousVersionReference == null) {
                logger.debug("[getVersionsAscending] no more previous versions.");
                break;
            } else {
                versionName = getVersionName(previousVersionReference);
                logger.debug("[getVersionsAscending] previous version is [{}]", versionName);
                result.add(previousVersionReference);
            }
            i++;
        }

        if (i > versionQuantity) {
            logger.error("[getVersionsAscending] Infinite loop detected in versions preceding [{}].", versionName);
            return Collections.emptyList();
        }
        return result;
    }

    @Override
    public DocumentReference getInheritedVersionedContentReference(DocumentReference documentReference)
        throws XWikiException, QueryException
    {
        DocumentReference versionDocumentReference = getVersionedContentReference(documentReference);

        return versionDocumentReference != null
            ? getInheritedContentVersionReference(documentReference, versionDocumentReference) : documentReference;
    }

    @Override
    public DocumentReference getInheritedContentVersionReference(DocumentReference pageReference,
        DocumentReference versionReference) throws QueryException, XWikiException
    {
        if (pageReference == null || versionReference == null) {
            return null;
        }

        // TO DO: check if the page is unversioned, or not
        XWikiContext xcontext = this.getXWikiContext();
        XWiki xwiki = xcontext.getWiki();

        String versionName = getVersionName(versionReference);

        logger.debug("[getInheritedContentVersionReference] versionName : [{}]", versionName);

        while (versionName != null && !versionName.isEmpty()) {
            DocumentReference versionedContentRef =
                new DocumentReference(new EntityReference(versionName, EntityType.DOCUMENT, pageReference.getParent()));

            logger.debug("[getInheritedContentVersionReference] versionedContentRef : [{}]", versionedContentRef);

            if (xwiki.exists(versionedContentRef, xcontext)) {
                // Content exists for this version of the page
                return versionedContentRef;
            } else {
                // Content does not exists for this version. Lets check if there is content in a version to be
                // inherited
                return getPrecedingContentVersionReference(pageReference, versionReference);
            }
        }

        return null;
    }

    private DocumentReference getPrecedingContentVersionReference(DocumentReference pageReference,
        DocumentReference versionReference) throws QueryException, XWikiException
    {
        if (versionReference == null) {
            // This is the first version in the tree, there's nothing to inherit from
            return null;
        }

        XWikiContext xcontext = this.getXWikiContext();
        XWiki xwiki = xcontext.getWiki();
        DocumentReference versionedContentReference = new DocumentReference(
            new EntityReference(getVersionName(versionReference), EntityType.DOCUMENT, pageReference.getParent()));

        if (xwiki.exists(versionedContentReference, xcontext)) {
            // Found the content corresponding to the given version
            return versionedContentReference;
        } else {
            // No versioned content found, so search in the preceding version
            return xwiki.exists(versionedContentReference, xcontext) ? versionedContentReference
                : getPrecedingContentVersionReference(pageReference, getPreviousVersion(versionReference));
        }
    }

    @Override
    public DocumentReference getPreviousVersion(DocumentReference versionReference) throws XWikiException
    {
        XWikiContext xcontext = this.getXWikiContext();

        if (versionReference == null) {
            return null;
        }

        XWikiDocument versionDocument = xcontext.getWiki().getDocument(versionReference, xcontext);
        BaseObject versionObject = versionDocument.getXObject(BookVersionsConstants.VERSION_CLASS_REFERENCE);
        if (versionObject == null) {
            logger.warn("Version [{}] is missing the object [{}].", versionDocument.getDocumentReference().toString(),
                BookVersionsConstants.VERSION_CLASS_REFERENCE.toString());
            return null;
        }

        String previousVersion = versionObject.getStringValue(BookVersionsConstants.VERSION_PROP_PRECEDINGVERSION);
        return previousVersion != null && !previousVersion.isBlank()
            ? referenceResolver.resolve(previousVersion, versionReference)
            : null;
    }

    @Override
    public DocumentReference getInheritedContentReference(DocumentReference pageReference,
        DocumentReference versionReference) throws QueryException, XWikiException
    {
        if (pageReference == null || versionReference == null) {
            return null;
        }

        XWikiContext xcontext = this.getXWikiContext();
        DocumentReference versionPageReference = getInheritedContentVersionReference(pageReference, versionReference);

        logger.debug("[getInheritedContentReference] versionPageReference : [{}]", versionPageReference);

        if (versionPageReference != null) {
            String versionName = getVersionName(versionPageReference);

            logger.debug("[getInheritedContentReference] versionName : [{}]", versionName);

            if (versionName != null) {
                DocumentReference versionedContentRef = new DocumentReference(
                    new EntityReference(versionName, EntityType.DOCUMENT, pageReference.getParent()));

                logger.debug("[getInheritedContentReference] versionedContentRef : [{}]", versionedContentRef);

                if (xcontext.getWiki().exists(versionedContentRef, xcontext)) {
                    // Content exists for this version of the page
                    return versionedContentRef;
                }
            }
        }

        return null;
    }

    private SpaceReference getSpaceReference(EntityReference entityReference)
    {
        EntityReference spaceEntityReference = entityReference.extractReference(EntityType.SPACE);

        return entityReference != null && spaceEntityReference != null ? spaceEntityReference instanceof SpaceReference
            ? (SpaceReference) spaceEntityReference : new SpaceReference(spaceEntityReference) : null;
    }

    @Override
    public void setLibrary(DocumentReference bookReference, DocumentReference libraryReference)
        throws QueryException, XWikiException
    {
        setLibrary(bookReference, libraryReference,
            referenceResolver.resolve(getCollectionVersions(libraryReference).get(0), libraryReference));
    }

    @Override
    public void setLibrary(DocumentReference bookReference, DocumentReference libraryReference,
        DocumentReference libraryVersionReference) throws QueryException, XWikiException
    {
        if (isBook(bookReference) && isLibrary(libraryReference)
            && isFromLibrary(libraryReference, libraryVersionReference))
        {
            List<String> versionsLocalRef = getCollectionVersions(bookReference);
            for (String versionLocalRef : versionsLocalRef) {
                DocumentReference versionRef = referenceResolver.resolve(versionLocalRef);
                setVersionLibrary(versionRef, libraryReference, libraryVersionReference);
            }
        }
    }

    private void setVersionLibrary(DocumentReference versionReference, DocumentReference libraryReference,
        DocumentReference libraryVersionReference) throws XWikiException
    {
        if (versionReference == null || libraryReference == null || libraryVersionReference == null) {
            return;
        }

        XWikiContext xcontext = getXWikiContext();
        XWiki xwiki = xcontext.getWiki();
        XWikiDocument versionDoc = xwiki.getDocument(versionReference, xcontext).clone();
        if (versionDoc == null) {
            return;
        }

        List<BaseObject> libRefObjects =
            versionDoc.getXObjects(BookVersionsConstants.BOOKLIBRARYREFERENCE_CLASS_REFERENCE);
        boolean createObject = true;
        for (BaseObject libRefObject : libRefObjects) {
            if (libRefObject == null) {
                continue;
            }
            String libraryPropValue =
                libRefObject.getStringValue(BookVersionsConstants.BOOKLIBRARYREFERENCE_PROP_LIBRARY);

            if (libraryPropValue != null
                && libraryReference.equals(referenceResolver.resolve(libraryPropValue, libraryReference)))
            {

                if (libraryVersionReference.equals(referenceResolver.resolve(
                    libRefObject.getStringValue(BookVersionsConstants.BOOKLIBRARYREFERENCE_PROP_LIBRARYVERSION),
                    libraryVersionReference)))
                {
                    // The version library is already set with the proper value, nothing to do
                    return;
                } else {
                    // The library configuration object already exists but without the proper value
                    libRefObject.set(BookVersionsConstants.BOOKLIBRARYREFERENCE_PROP_LIBRARYVERSION,
                        libraryVersionReference, xcontext);
                    createObject = false;
                }
            }
        }

        if (createObject) {
            // No object already existing for the library configuration, add one
            BaseObject newObject =
                versionDoc.newXObject(BookVersionsConstants.BOOKLIBRARYREFERENCE_CLASS_REFERENCE, xcontext);
            newObject.set(BookVersionsConstants.BOOKLIBRARYREFERENCE_PROP_LIBRARY, libraryReference, xcontext);
            newObject.set(BookVersionsConstants.BOOKLIBRARYREFERENCE_PROP_LIBRARYVERSION, libraryVersionReference,
                xcontext);
        }
        UserReference userReference = userReferenceResolver.resolve(xcontext.getUserReference());
        versionDoc.getAuthors().setEffectiveMetadataAuthor(userReference);
        versionDoc.getAuthors().setOriginalMetadataAuthor(userReference);
        xwiki.saveDocument(versionDoc, "Setting version configuration for library ["
            + libraryReference.getParent().toString() + "]: [" + libraryVersionReference.toString() + "].", xcontext);
    }

    @Override
    public DocumentReference getConfiguredLibraryVersion(DocumentReference documentReference,
        DocumentReference libraryReference) throws XWikiException, QueryException
    {
        if (!isLibrary(libraryReference)) {
            return null;
        }
        DocumentReference collectionRef = getVersionedCollectionReference(documentReference);
        String selectedVersionStringRef = getSelectedVersion(documentReference);
        if (isVersionedContent(documentReference)) {
            SpaceReference versionParentSpaceReference =
                new SpaceReference(new EntityReference(BookVersionsConstants.VERSIONS_LOCATION, EntityType.SPACE,
                    collectionRef.getParent()));
            DocumentReference versionDocumentReference = new DocumentReference(
                new EntityReference(documentReference.getName(), EntityType.DOCUMENT, versionParentSpaceReference));
            selectedVersionStringRef = localSerializer.serialize(versionDocumentReference);
        }
        if (selectedVersionStringRef == null || selectedVersionStringRef.isEmpty()) {
            // in case no version has been selected yet, get the most recent one
            List<String> versions = getCollectionVersions(collectionRef);
            if (versions.size() == 0) {
                return null;
            }
            selectedVersionStringRef = getCollectionVersions(collectionRef).get(0);
        }
        DocumentReference selectedVersionRef = referenceResolver.resolve(selectedVersionStringRef, collectionRef);
        return getConfiguredLibraryVersion(collectionRef, libraryReference, selectedVersionRef);
    }

    /**
     * Get the library version configured for the given book version which uses the given library.
     *
     * @param bookReference the book
     * @param libraryReference the library used
     * @param versionReference the version of the book
     * @return the reference of the library version configured for the book version
     * @throws XWikiException
     */
    private DocumentReference getConfiguredLibraryVersion(DocumentReference bookReference,
        DocumentReference libraryReference, DocumentReference versionReference) throws XWikiException
    {
        if (!isBook(bookReference) || !isLibrary(libraryReference) || !isVersion(versionReference)) {
            return null;
        }

        XWikiContext xcontext = getXWikiContext();
        XWiki xwiki = xcontext.getWiki();
        XWikiDocument selectedVersionDoc = xwiki.getDocument(versionReference, xcontext);
        List<BaseObject> libRefObjects =
            selectedVersionDoc.getXObjects(BookVersionsConstants.BOOKLIBRARYREFERENCE_CLASS_REFERENCE);
        for (BaseObject libRefObject : libRefObjects) {
            if (libRefObject == null) {
                continue;
            }
            if (libRefObject != null && libraryReference.equals(referenceResolver.resolve(
                libRefObject.getStringValue(BookVersionsConstants.BOOKLIBRARYREFERENCE_PROP_LIBRARY),
                libraryReference)))
            {
                return referenceResolver.resolve(
                    libRefObject.getStringValue(BookVersionsConstants.BOOKLIBRARYREFERENCE_PROP_LIBRARYVERSION),
                    libraryReference);
            }
        }

        return null;
    }

    @Override
    public DocumentReference getLinkedLibraryContentReference(DocumentReference documentReference,
        DocumentReference keyReference) throws XWikiException, QueryException
    {
        if (documentReference == null || keyReference == null) {
            return null;
        }

        DocumentReference libraryRef = getVersionedCollectionReference(keyReference);
        if (isLibrary(libraryRef) && isPage(keyReference)) {
            // the passed reference is part of a library
            if (isVersionedPage(keyReference)) {
                // versioned page => get the content depending on the book configuration
                DocumentReference libraryVersionRef = getConfiguredLibraryVersion(documentReference, libraryRef);
                return getInheritedContentReference(keyReference, libraryVersionRef);
            } else {
                // unversioned page
                return keyReference;
            }
        }

        return null;
    }

    @Override
    public List<DocumentReference> getUsedLibraries(DocumentReference bookReference)
        throws XWikiException, QueryException
    {
        List<DocumentReference> result = new ArrayList<DocumentReference>();
        if (bookReference != null && isBook(bookReference)) {
            logger.debug("[getUsedLibraries] Get libraries used in [{}].", bookReference);

            SpaceReference spaceReference = bookReference.getLastSpaceReference();
            String spaceSerialized = localSerializer.serialize(spaceReference);
            String spacePrefix = spaceSerialized.replaceAll("([%_/])", "/$1").concat(".%");

            // Query inspired from getDocumentReferences of DefaultModelBridge.java in xwiki-platform
            List<String> resultStrings = this.queryManagerProvider.get()
                .createQuery("select distinct obj.libraryReference from Document doc, "
                    + "doc.object(BookVersions.Code.LibraryReferenceClass) as obj where doc.space "
                    + "like :space escape '/' order by obj.libraryReference asc", Query.XWQL)
                .bindValue("space", spacePrefix).setWiki(bookReference.getWikiReference().getName()).execute();

            for (String resultString : resultStrings) {
                if (StringUtils.isNotEmpty(resultString)) {
                    result.add(currentMixedReferenceResolver.resolve(resultString, bookReference));
                }
            }

            logger.debug("[getUsedLibraries] Libraries used in book: [{}]", result);
        }

        return result;
    }

    @Override
    public Map<DocumentReference, DocumentReference> getUsedPublishedLibraries(DocumentReference bookReference,
        DocumentReference versionReference) throws XWikiException, QueryException
    {
        Map<DocumentReference, DocumentReference> result = new HashMap<>();
        if (bookReference != null && isBook(bookReference)) {
            List<DocumentReference> usedLibraries = getUsedLibraries(bookReference);
            for (DocumentReference libraryReference : usedLibraries) {
                DocumentReference libraryVersionReference =
                    getConfiguredLibraryVersion(bookReference, libraryReference, versionReference);
                if (libraryVersionReference == null) {
                    logger.warn("Library [{}] is used in book [{}] but no library's version has been configured "
                        + "for book's version [{}].", libraryReference, bookReference, versionReference);
                    result.put(libraryReference, null);
                    continue;
                }
                DocumentReference publishedSpace =
                    getCollectionPublishedSpace(libraryReference, libraryVersionReference.getName(), libraryReference);
                if (publishedSpace == null) {
                    logger.warn(
                        "Library [{}], configured in book [{}] to use version [{}]" + " doesn't seem to be published.",
                        libraryReference, bookReference, libraryVersionReference);
                    result.put(libraryReference, null);
                    continue;
                }
                result.put(libraryReference, publishedSpace);
            }
        }
        return result;
    }

    /**
     * Get the published space for each of the libraries used in the given book, for the current version and the
     * preceding ones.
     *
     * @param bookReference the reference of the book
     * @param versionReference the version of the book, which corresponds to a library's version in the book
     *     configuration
     * @return the published space reference of each library used in the book as a map of {bookVersionName, {
     *     libraryReference, publishedSpaceReference}}
     * @throws XWikiException In case a getDocument method or a check of type (isBook, ...) has an issue
     * @throws QueryException If any exception occurs while querying the database for the used libraries in the
     *     book.
     */
    private Map<String, Map<DocumentReference, DocumentReference>> getUsedPublishedLibrariesWithInheritance(
        DocumentReference bookReference, DocumentReference versionReference) throws XWikiException, QueryException
    {
        Map<String, Map<DocumentReference, DocumentReference>> result = new HashMap<>();
        if (bookReference != null && isBook(bookReference)) {
            List<DocumentReference> versions = getVersionsAscending(bookReference, versionReference);
            List<DocumentReference> usedLibraries = getUsedLibraries(bookReference);
            for (DocumentReference ascendingVersionReference : versions) {
                Map<DocumentReference, DocumentReference> libResult = new HashMap<>();
                String versionName = getVersionName(ascendingVersionReference);
                for (DocumentReference libraryReference : usedLibraries) {
                    if (libraryReference == null) {
                        continue;
                    }
                    DocumentReference libraryVersionReference =
                        getConfiguredLibraryVersion(bookReference, libraryReference, ascendingVersionReference);
                    if (libraryVersionReference == null) {
                        logger.warn("Library [{}] is used in book [{}] but no library's version has been configured "
                            + "for book's version [{}].", libraryReference, bookReference, ascendingVersionReference);
                        libResult.put(libraryReference, null);
                        continue;
                    }
                    DocumentReference publishedSpace =
                        getCollectionPublishedSpace(libraryReference, libraryVersionReference.getName(),
                            libraryReference);
                    if (publishedSpace == null) {
                        logger.warn("Library [{}], configured in book [{}] to use version [{}] doesn't seem to be "
                                + "published.", libraryReference, bookReference, libraryVersionReference);
                        libResult.put(libraryReference, null);
                        continue;
                    }
                    libResult.put(libraryReference, publishedSpace);
                }
                result.put(versionName, libResult);
            }
        }
        return result;
    }

    @Override
    public void switchDeletedMark(DocumentReference documentReference) throws XWikiException, QueryException
    {
        if (documentReference == null) {
            return;
        }

        XWikiContext xcontext = this.getXWikiContext();
        XWiki xwiki = xcontext.getWiki();
        XWikiDocument document = xwiki.getDocument(documentReference, xcontext).clone();

        if (isMarkedDeleted(documentReference)) {
            BaseObject object = document.getXObject(BookVersionsConstants.MARKEDDELETED_CLASS_REFERENCE);
            if (object != null) {
                document.removeXObject(object);
                UserReference userReference = userReferenceResolver.resolve(xcontext.getUserReference());
                document.getAuthors().setEffectiveMetadataAuthor(userReference);
                document.getAuthors().setOriginalMetadataAuthor(userReference);
                xwiki.saveDocument(document, "Unmarked document as \"Deleted\"", xcontext);
            }
        } else {
            document.newXObject(BookVersionsConstants.MARKEDDELETED_CLASS_REFERENCE, xcontext);
            UserReference userReference = userReferenceResolver.resolve(xcontext.getUserReference());
            document.getAuthors().setEffectiveMetadataAuthor(userReference);
            document.getAuthors().setOriginalMetadataAuthor(userReference);
            xwiki.saveDocument(document, "Marked document as \"Deleted\"", xcontext);
        }
    }

    @Override
    public void switchToVersioned(DocumentReference unversionedDocumentReference,
        DocumentReference versionedDocumentReference) throws XWikiException
    {
        if (unversionedDocumentReference == null || versionedDocumentReference == null ||
            !isPage(unversionedDocumentReference) || isVersionedContent(unversionedDocumentReference) ) {
            return;
        }

        XWikiContext xcontext = this.getXWikiContext();
        XWiki xwiki = xcontext.getWiki();
        XWikiDocument unversionedDocument = xwiki.getDocument(unversionedDocumentReference, xcontext).clone();
        XWikiDocument versionedDocument = xwiki.getDocument(versionedDocumentReference, xcontext).clone();

        boolean result = copyContentsToNewVersion(unversionedDocument, versionedDocument, xcontext,
            BookVersionsConstants.UNVERSIONEDTOVERSIONED_REMOVEDOBJECTS);
        if (!result) {
            // Copy did not happen
            return;
        }

        // Change the unversioned document to versioned
        BaseObject xObject = unversionedDocument.getXObject(BookVersionsConstants.BOOKPAGE_CLASS_REFERENCE);
        if (xObject == null) {
            xObject = unversionedDocument.newXObject(BookVersionsConstants.BOOKPAGE_CLASS_REFERENCE, xcontext);
        }
        xObject.set(BookVersionsConstants.BOOKPAGE_PROP_UNVERSIONED, 0, xcontext);
        xwiki.saveDocument(unversionedDocument,
            "Changed to versioned in Version "+ getVersionName(versionedDocumentReference), xcontext);

        // Add the necessary objects to the versioned document
        for (EntityReference objectRef : BookVersionsConstants.UNVERSIONEDTOVERSIONED_ADDOBJECTS) {
            BaseObject objectList = versionedDocument.getXObject(objectRef);
            if (objectList == null) {
                versionedDocument.newXObject(objectRef, xcontext);
            }
        }
        xwiki.saveDocument(versionedDocument,
            "Created versioned content for Version "+ getVersionName(versionedDocumentReference), xcontext);
    }

    @Override
    public void switchToUnversioned(DocumentReference versionedDocumentReference,
        DocumentReference unversionedDocumentReference) throws XWikiException
    {
        if (unversionedDocumentReference == null || versionedDocumentReference == null ||
            !isVersionedContent(versionedDocumentReference) ) {
            return;
        }

        XWikiContext xcontext = this.getXWikiContext();
        XWiki xwiki = xcontext.getWiki();
        XWikiDocument unversionedDocument = xwiki.getDocument(unversionedDocumentReference, xcontext).clone();
        XWikiDocument versionedDocument = xwiki.getDocument(versionedDocumentReference, xcontext).clone();

        BaseObject originalCollectionClass = unversionedDocument.getXObject(BookVersionsConstants.BOOK_CLASS_REFERENCE);
        if (originalCollectionClass == null) {
            originalCollectionClass = unversionedDocument.getXObject(BookVersionsConstants.LIBRARY_CLASS_REFERENCE);
        }
        List<BaseObject> collectionPublications =
            unversionedDocument.getXObjects(BookVersionsConstants.PUBLICATION_CLASS_REFERENCE);
        List<BaseObject> collectionPublicationsClones = new ArrayList<>();
        for (BaseObject collectionPublication : collectionPublications) {
            if (collectionPublication != null) {
                collectionPublicationsClones.add(collectionPublication.clone());
            }
        }

        boolean result = copyContentsToNewVersion(versionedDocument, unversionedDocument, xcontext,
            BookVersionsConstants.VERSIONEDTOUNVERSIONED_REMOVEDOBJECTS);
        if (!result) {
            // Copy did not happen
            return;
        }

        // Change the unversioned document to unversioned value and add eventual top page data
        if (originalCollectionClass != null) {
            // Set the collection's top page if it is the case
            unversionedDocument.newXObject(originalCollectionClass.getXClassReference(), xcontext);
            if (collectionPublicationsClones != null && collectionPublicationsClones.size() > 0) {
                for (BaseObject collectionPublication : collectionPublicationsClones) {
                    if (collectionPublication == null) {
                        continue;
                    }
                    BaseObject tmpObject =
                        unversionedDocument.newXObject(BookVersionsConstants. PUBLICATION_CLASS_REFERENCE, xcontext);
                    tmpObject.set(BookVersionsConstants.PUBLICATION_PROP_ID,
                        collectionPublication.getStringValue(BookVersionsConstants.PUBLICATION_PROP_ID), xcontext);
                    tmpObject.set(BookVersionsConstants.PUBLICATION_PROP_SOURCE,
                        collectionPublication.getStringValue(BookVersionsConstants.PUBLICATION_PROP_SOURCE), xcontext);
                    tmpObject.set(BookVersionsConstants.PUBLICATION_PROP_PUBLISHEDSPACE,
                        collectionPublication.getStringValue(BookVersionsConstants.PUBLICATION_PROP_PUBLISHEDSPACE),
                        xcontext);
                }
            }
        }
        BaseObject xObject = unversionedDocument.getXObject(BookVersionsConstants.BOOKPAGE_CLASS_REFERENCE);
        if (xObject == null) {
            xObject = unversionedDocument.newXObject(BookVersionsConstants.BOOKPAGE_CLASS_REFERENCE, xcontext);
        }
        xObject.set(BookVersionsConstants.BOOKPAGE_PROP_UNVERSIONED, 1, xcontext);
        xwiki.saveDocument(unversionedDocument,
            "Changed to unversioned from Version "+ getVersionName(versionedDocumentReference), xcontext);
    }

    @Override
    public String publish(DocumentReference configurationReference) throws JobException
    {
        if (configurationReference == null) {
            return null;
        }

        DefaultRequest jobRequest = new DefaultRequest();
        String jobId = BookVersionsConstants.PUBLICATION_JOBID_PREFIX
            + BookVersionsConstants.PUBLICATION_JOBID_SEPARATOR + configurationReference.getName()
            + BookVersionsConstants.PUBLICATION_JOBID_SEPARATOR + Instant.now().toString();
        if (jobId != null) {
            jobRequest.setId(jobId);
            jobRequest.setProperty("configurationReference", configurationReference);
            // The context won't be full in publishInternal as it is executed by a job, so the user executing the
            // publication and locale have to be passed as parameters.
            XWikiContext xcontext = this.getXWikiContext();
            jobRequest.setProperty("userReference", xcontext.getUserReference());
            jobRequest.setProperty("userLocale", xcontext.getLocale());
            jobExecutor.execute(BookVersionsConstants.PUBLICATIONJOB_TYPE, jobRequest);
        }
        return jobId;
    }

    @Override
    public Map<String, Object> loadPublicationConfiguration(DocumentReference configurationReference)
        throws XWikiException
    {
        Map<String, Object> configuration = new HashMap<String, Object>();
        logger.debug("[loadPublicationConfiguration] Loading publication configuration from [{}]",
            configurationReference);
        if (configurationReference == null) {
            logger.error("Configuration reference is null");
            return configuration;
        }

        XWikiContext xcontext = this.getXWikiContext();
        XWiki xwiki = xcontext.getWiki();
        XWikiDocument configurationDoc = xwiki.getDocument(configurationReference, xcontext);
        BaseObject configurationObject =
            configurationDoc.getXObject(BookVersionsConstants.PUBLICATIONCONFIGURATION_CLASS_REFERENCE);

        if (configurationObject == null) {
            logger.error("[loadPublicationConfiguration] Configuration page has no [{}]",
                BookVersionsConstants.PUBLICATIONCONFIGURATION_CLASS_REFERENCE);
            return configuration;
        }

        String sourceReferenceString =
            configurationObject.getStringValue(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_SOURCE);
        String destinationReferenceString =
            configurationObject.getStringValue(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_DESTINATIONSPACE);
        String versionReferenceString =
            configurationObject.getStringValue(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_VERSION);
        String behaviour =
            configurationObject.getStringValue(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_PUBLISHBEHAVIOUR);
        if (sourceReferenceString == null || sourceReferenceString.isBlank() || destinationReferenceString == null
            || behaviour == null || destinationReferenceString.isBlank() || versionReferenceString.isBlank())
        {
            logger.error("One of the mandatory element in the configuration (source, destination, version or behaviour) "
                + "is missing.");
            return configuration;
        }

        // The source is provided as space, but we need a complete page location.WebHome reference
        SpaceReference sourceSpaceReference =
            spaceReferenceResolver.resolve(sourceReferenceString, configurationReference.getWikiReference());

        DocumentReference sourceReference = new DocumentReference(new EntityReference(
            this.getXWikiContext().getWiki().DEFAULT_SPACE_HOMEPAGE, EntityType.DOCUMENT, sourceSpaceReference));

        configuration.put(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_SOURCE, sourceReference);

        configuration.put(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_DESTINATIONSPACE,
            spaceReferenceResolver.resolve(destinationReferenceString, configurationReference.getWikiReference()));
        configuration.put(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_VERSION,
            referenceResolver.resolve(versionReferenceString, configurationReference.getWikiReference()));
        configuration.put(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_PUBLISHBEHAVIOUR, behaviour);

        String variantReferenceString =
            configurationObject.getStringValue(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_VARIANT);
        configuration.put(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_VARIANT,
            (variantReferenceString == null || variantReferenceString.isBlank()) ? null
                : referenceResolver.resolve(variantReferenceString, configurationReference.getWikiReference()));

        configuration.put(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_LANGUAGE,
            configurationObject.getStringValue(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_LANGUAGE));
        configuration.put(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_PUBLISHONLYCOMPLETE, configurationObject
            .getIntValue(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_PUBLISHONLYCOMPLETE) != 0);
        configuration.put(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_PUBLISHPAGEORDER,
            configurationObject.getIntValue(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_PUBLISHPAGEORDER) != 0);
        configuration.put(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_TITLE,
            configurationObject.getStringValue(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_TITLE));

        logger.debug("[loadPublicationConfiguration] Configuration loaded: [{}].", configuration);

        return configuration;
    }

    @Override
    public List<Map<String, Object>> previewPublication(
            DocumentReference configurationReference,
            DocumentReference userDocumentReference)
            throws XWikiException, QueryException, ComponentLookupException, ParseException
    {
        List<Map<String, Object>> previewLines = new ArrayList<>();

        if (configurationReference == null || userDocumentReference == null) {
            Map<String, Object> errorLine = new HashMap<>();
            errorLine.put("message", "Cannot preview publication: null configuration or user reference");
            errorLine.put("variable", null);
            previewLines.add(errorLine);
            return previewLines;
        }

        // Load Publication Configuration
        Map<String, Object> configuration = loadPublicationConfiguration(configurationReference);
        if (configuration == null || configuration.isEmpty()) {
            Map<String, Object> errorLine = new HashMap<>();
            errorLine.put("message", "Incomplete publication configuration found");
            errorLine.put("variable", configurationReference);
            previewLines.add(errorLine);
            return previewLines;
        }

        DocumentReference sourceReference =
                (DocumentReference) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_SOURCE);
        if (sourceReference == null) {
            Map<String, Object> errorLine = new HashMap<>();
            errorLine.put("message", "No source reference found in configuration");
            errorLine.put("variable", configurationReference);
            previewLines.add(errorLine);
            return previewLines;
        }

        SpaceReference targetReference =
                (SpaceReference) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_DESTINATIONSPACE);
        if (targetReference == null) {
            Map<String, Object> errorLine = new HashMap<>();
            errorLine.put("message", "No target reference found in configuration");
            errorLine.put("variable", configurationReference);
            previewLines.add(errorLine);
            return previewLines;
        }

        String publicationBehaviour =
                (String) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_PUBLISHBEHAVIOUR);
        if (publicationBehaviour == null) {
            Map<String, Object> errorLine = new HashMap<>();
            errorLine.put("message", "No publication behaviour found in configuration");
            errorLine.put("variable", configurationReference);
            previewLines.add(errorLine);
            return previewLines;
        }

        XWikiContext xcontext = this.getXWikiContext();
        XWiki xwiki = xcontext.getWiki();

        // Handle the source reference format, ensuring it's a .WebHome reference if needed
        if (!Objects.equals(sourceReference.getName(), this.getXWikiContext().getWiki().DEFAULT_SPACE_HOMEPAGE)) {
            SpaceReference sourceParentSpaceReference = new SpaceReference(
                    new EntityReference(sourceReference.getName(), EntityType.SPACE, sourceReference.getParent()));
            sourceReference =
                    new DocumentReference(new EntityReference(this.getXWikiContext().getWiki().DEFAULT_SPACE_HOMEPAGE,
                            EntityType.DOCUMENT, sourceParentSpaceReference));
        }
        // Check if the source exists
        if (!xwiki.exists(sourceReference, xcontext)) {
            Map<String, Object> errorLine = new HashMap<>();
            errorLine.put("message", "Source reference does not exist");
            errorLine.put("variable", sourceReference);
            previewLines.add(errorLine);
            return previewLines;
        }

        // Get target documents
        DocumentReference targetDocumentReference =
                new DocumentReference(new EntityReference(xwiki.DEFAULT_SPACE_HOMEPAGE,
                        EntityType.DOCUMENT, targetReference));
        List<String> subTargetDocumentsString = queryPages(targetDocumentReference);

        // Add info about target space status
        if (publicationBehaviour.equals(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_PUBLISHBEHAVIOUR_CANCEL)
                && !isEmptyTargetSpace(subTargetDocumentsString, targetDocumentReference)) {
            Map<String, Object> infoLine = new HashMap<>();
            infoLine.put("message", "Publication will be canceled because target is not empty");

            // Include both the target reference and the list of existing documents
            Map<String, Object> cancelInfo = new HashMap<>();
            cancelInfo.put("targetSpace", targetReference);
            cancelInfo.put("existingDocs", new ArrayList<>(subTargetDocumentsString));
            infoLine.put("variable", cancelInfo);

            previewLines.add(infoLine);
            return previewLines;
        } else if (publicationBehaviour.equals(
                BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_PUBLISHBEHAVIOUR_REPUBLISH)) {
            Map<String, Object> infoLine = new HashMap<>();
            infoLine.put("message", "Target space will be emptied before publication");
            infoLine.put("variable", targetReference);
            previewLines.add(infoLine);

            // Add list of documents that would be removed
            if (xwiki.exists(targetDocumentReference, xcontext)) {
                subTargetDocumentsString.add(localSerializer.serialize(targetDocumentReference));
            }

            List<DocumentReference> docsToRemoveRefs = new ArrayList<>();
            for (String docToRemove : subTargetDocumentsString) {
                DocumentReference docRef = referenceResolver.resolve(docToRemove, targetReference.getWikiReference());
                docsToRemoveRefs.add(docRef);

                Map<String, Object> removeLine = new HashMap<>();
                removeLine.put("message", "Document would be removed");
                removeLine.put("variable", docRef);
                previewLines.add(removeLine);
            }

            // Add a summary line with all documents to be removed
            Map<String, Object> summaryLine = new HashMap<>();
            summaryLine.put("message", "All documents to be removed from target space");
            summaryLine.put("variable", docsToRemoveRefs);
            previewLines.add(summaryLine);
        }

        DocumentReference collectionReference = getVersionedCollectionReference(sourceReference);
        XWikiDocument collection = collectionReference != null ? xwiki.getDocument(collectionReference, xcontext) : null;

        if (collection == null) {
            Map<String, Object> errorLine = new HashMap<>();
            errorLine.put("message", "Could not determine collection reference");
            errorLine.put("variable", sourceReference);
            previewLines.add(errorLine);
            return previewLines;
        }

        DocumentReference versionReference =
                (DocumentReference) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_VERSION);
        XWikiDocument version = versionReference != null ? xwiki.getDocument(versionReference, xcontext) : null;

        if (version == null) {
            Map<String, Object> errorLine = new HashMap<>();
            errorLine.put("message", "Version reference does not exist");
            errorLine.put("variable", versionReference);
            previewLines.add(errorLine);
            return previewLines;
        }

        // Add collection and version info
        Map<String, Object> infoLine = new HashMap<>();
        infoLine.put("message", "Publishing from collection and version");
        infoLine.put("variable", collection.getTitle() + " - " + version.getTitle());
        previewLines.add(infoLine);

        DocumentReference variantReference =
                (DocumentReference) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_VARIANT);
        XWikiDocument variant = variantReference != null ? xwiki.getDocument(variantReference, xcontext) : null;

        if (variantReference != null && variant == null) {
            Map<String, Object> errorLine = new HashMap<>();
            errorLine.put("message", "Variant reference does not exist");
            errorLine.put("variable", variantReference);
            previewLines.add(errorLine);
            return previewLines;
        }

        // Check if needed libraries are published
        Map<String, Map<DocumentReference, DocumentReference>> publishedLibraries =
                collection != null && versionReference != null && isBook(collectionReference)
                        ? getUsedPublishedLibrariesWithInheritance(collection.getDocumentReference(), versionReference) : null;

        // Add library info
        if (publishedLibraries != null) {
            String versionName = getVersionName(versionReference);
            Map<DocumentReference, DocumentReference> libsForVersion = publishedLibraries.get(versionName);

            if (libsForVersion != null) {
                for (Map.Entry<DocumentReference, DocumentReference> entry : libsForVersion.entrySet()) {
                    DocumentReference libraryRef = entry.getKey();
                    DocumentReference publishedRef = entry.getValue();

                    Map<String, Object> libLine = new HashMap<>();
                    // Create a map with detailed library information
                    Map<String, Object> libraryInfo = new HashMap<>();
                    libraryInfo.put("libraryRef", libraryRef);
                    libraryInfo.put("publishedRef", publishedRef);

                    // Get the configured library version
                    try {
                        DocumentReference libraryVersionRef = getConfiguredLibraryVersion(collectionReference, libraryRef, versionReference);
                        libraryInfo.put("versionRef", libraryVersionRef);
                    } catch (Exception e) {
                        logger.debug("Could not get library version reference: {}", e.getMessage());
                    }

                    if (publishedRef != null) {
                        libLine.put("message", "Library is published and will be used");
                    } else {
                        libLine.put("message", "WARNING: Library is not published");
                    }
                    libLine.put("variable", libraryInfo);
                    previewLines.add(libLine);
                }
            }
        }

        // Get the page tree and preview each page
        List<String> pageReferenceTree = getPageReferenceTree(sourceReference);
        List<DocumentReference> markedAsDeletedReferences = new ArrayList<>();

        Map<String, Object> pagesLine = new HashMap<>();
        pagesLine.put("message", "Pages to process");
        pagesLine.put("variable", pageReferenceTree.size());
        previewLines.add(pagesLine);

        // Process each page in the tree
        for (String pageStringReference : pageReferenceTree) {
            if (pageStringReference == null) {
                Map<String, Object> errorLine = new HashMap<>();
                errorLine.put("message", "Page reference is null");
                errorLine.put("variable", null);
                previewLines.add(errorLine);
                continue;
            }

            DocumentReference pageReference = referenceResolver.resolve(pageStringReference, configurationReference);

            if (!isPage(pageReference)) {
                Map<String, Object> errorLine = new HashMap<>();
                errorLine.put("message", "Not a book page");
                errorLine.put("variable", pageReference);
                previewLines.add(errorLine);
                continue;
            }

            XWikiDocument page = xwiki.getDocument(pageReference, xcontext);

            // Get content page
            DocumentReference contentPageReference = getContentPage(page, configuration);
            if (contentPageReference == null) {
                Map<String, Object> errorLine = new HashMap<>();
                errorLine.put("message", "No content found for page");

                // Include full context about the page
                Map<String, Object> pageInfo = new HashMap<>();
                pageInfo.put("pageRef", pageReference);
                pageInfo.put("pageTitle", page.getTitle());
                pageInfo.put("isVersioned", !isVersionedPage(pageReference));

                errorLine.put("variable", pageInfo);
                previewLines.add(errorLine);
                continue;
            }

            // Get published reference
            DocumentReference publishedReference =
                    getPublishedReference(pageReference, sourceReference, targetReference);
            if (publishedReference == null) {
                Map<String, Object> errorLine = new HashMap<>();
                errorLine.put("message", "Could not determine target reference");

                // Include page info
                Map<String, Object> pageInfo = new HashMap<>();
                pageInfo.put("pageRef", pageReference);
                pageInfo.put("collectionRef", collectionReference);
                pageInfo.put("targetRef", targetReference);

                errorLine.put("variable", pageInfo);
                previewLines.add(errorLine);
                continue;
            }

            // Check if content should be published
            Locale userLocale = xcontext.getLocale();

            // Add detailed page processing information
            Map<String, Object> processLine = new HashMap<>();
            processLine.put("message", "Processing page");

            Map<String, Object> processInfo = new HashMap<>();
            processInfo.put("pageRef", pageReference);
            processInfo.put("contentRef", contentPageReference);
            processInfo.put("targetRef", publishedReference);
            processInfo.put("title", page.getTitle());

            // Check if page exists at target already
            processInfo.put("targetExists", xwiki.exists(publishedReference, xcontext));

            // Add status and variant info
            processInfo.put("status", getPageStatus(pageReference));
            processInfo.put("variants", getPageVariants(pageReference));

            // Add language info if applicable
            String language = (String) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_LANGUAGE);
            if (StringUtils.isNotEmpty(language)) {
                processInfo.put("language", language);
                Map<String, Map<String, Object>> languageData = getLanguageData(contentPageReference);
                processInfo.put("languageData", languageData);
            }

            processLine.put("variable", processInfo);
            previewLines.add(processLine);

            // Preview publication status for this page
            if (!previewIsToBePublished(contentPageReference, variantReference, configuration, userLocale,
                previewLines, pageReference)) {
                if (isMarkedDeleted(contentPageReference)) {
                    // The original document is marked as deleted
                    markedAsDeletedReferences.add(publishedReference);

                    Map<String, Object> deleteLine = new HashMap<>();
                    deleteLine.put("message", "Page is marked as deleted and will be removed from target if exists");

                    // Include both source and target reference
                    Map<String, DocumentReference> deleteInfo = new HashMap<>();
                    deleteInfo.put("sourceMarkedDeleted", pageReference);
                    deleteInfo.put("targetToDelete", publishedReference);

                    deleteLine.put("variable", deleteInfo);
                    previewLines.add(deleteLine);
                }
                continue;
            }

            // This page will be published - include both source and destination in a single message
            Map<String, Object> publishLine = new HashMap<>();
            publishLine.put("message", "Page will be published");
            // Create a Map for source and destination to provide complete context
            Map<String, DocumentReference> publishInfo = new HashMap<>();
            publishInfo.put("source", pageReference);
            publishInfo.put("destination", publishedReference);
            publishInfo.put("content", contentPageReference);
            publishLine.put("variable", publishInfo);
            previewLines.add(publishLine);
        }

        // Add info about pages marked for deletion
        if (publicationBehaviour.equals(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_PUBLISHBEHAVIOUR_UPDATE)
                && markedAsDeletedReferences.size() > 0) {

            Map<String, Object> deletedLine = new HashMap<>();
            deletedLine.put("message", "Pages marked as deleted that will be removed from target");

            // Include both count and the actual references
            Map<String, Object> deletionInfo = new HashMap<>();
            deletionInfo.put("count", markedAsDeletedReferences.size());
            deletionInfo.put("references", new ArrayList<>(markedAsDeletedReferences));
            deletedLine.put("variable", deletionInfo);

            previewLines.add(deletedLine);

            for (DocumentReference deletedRef : markedAsDeletedReferences) {
                // Try to find the source document that was marked as deleted
                DocumentReference sourceDocRef = null;
                for (String pageStringRef : pageReferenceTree) {
                    DocumentReference pageRef = referenceResolver.resolve(pageStringRef, configurationReference);
                    DocumentReference pubRef = getPublishedReference(pageRef, sourceReference, targetReference);
                    if (pubRef != null && pubRef.equals(deletedRef)) {
                        sourceDocRef = pageRef;
                        break;
                    }
                }

                Map<String, Object> deleteLine = new HashMap<>();
                deleteLine.put("message", "Will be deleted from target");

                Map<String, DocumentReference> deleteInfo = new HashMap<>();
                deleteInfo.put("targetToDelete", deletedRef);
                deleteInfo.put("sourceMarkedDeleted", sourceDocRef);

                deleteLine.put("variable", deleteInfo);
                previewLines.add(deleteLine);
            }
        }

        // Add page order info if applicable
        if ((boolean) configuration.get("publishPageOrder")) {
            Map<String, Object> orderLine = new HashMap<>();
            orderLine.put("message", "Page order will be preserved");
            orderLine.put("variable", null);
            previewLines.add(orderLine);
        }

        return previewLines;
    }

    /**
     * Preview version of isToBePublished that collects information about why pages would or wouldn't be published
     * without affecting the actual publication logic.
     * @throws QueryException In case something goes wrong.
     * @throws XWikiException In case something goes wrong.
     */
    private boolean previewIsToBePublished(DocumentReference contentPageReference, DocumentReference variantReference,
                                           Map<String, Object> configuration, Locale userLocale,
                                           List<Map<String, Object>> previewLines, DocumentReference pageReference)
        throws QueryException, XWikiException
    {
        if (contentPageReference == null || configuration == null) {
            return false;
        }
        if (userLocale == null) {
            userLocale = new Locale(BookVersionsConstants.DEFAULT_LOCALE);
        }

        List<DocumentReference> variants = getPageVariants(contentPageReference);
        String status = getPageStatus(contentPageReference);
        boolean publishOnlyComplete =
                (boolean) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_PUBLISHONLYCOMPLETE);
        boolean excludePagesOutsideVariant = false;
        if (variantReference != null) {
            String variantQueryString = "select distinct prop.value from XWikiDocument as doc, BaseObject as obj, IntegerProperty as prop where obj.name = doc.fullName "
                + "and obj.className = :className and prop.id.id = obj.id and prop.id.name = :propName and doc.fullName = :variant and prop.value = :propValue";
            List<Integer> variantQueryResults = this.queryManagerProvider.get()
                .createQuery(variantQueryString, Query.HQL)
                .bindValue("className", localSerializer.serialize(BookVersionsConstants.VARIANT_CLASS_REFERENCE))
                .bindValue("propName", BookVersionsConstants.VARIANT_PROP_EXCLUDE)
                .bindValue("variant", localSerializer.serialize(variantReference))
                .bindValue("propValue", 1)
                .setWiki(variantReference.getWikiReference().getName())
                .execute();
            excludePagesOutsideVariant = variantQueryResults != null && variantQueryResults.size() > 0;
        }
        String language = (String) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_LANGUAGE);

        // Add detailed info for all checks
        Map<String, Object> reasonLine = new HashMap<>();
        Map<String, Object> reasonInfo = new HashMap<>();

        if (isMarkedDeleted(contentPageReference)) {
            // Page is marked as deleted
            reasonInfo.put("pageRef", pageReference);
            reasonInfo.put("reason", "marked_deleted");
            reasonInfo.put("status", status);

            reasonLine.put("message", "Page is marked as deleted");
            reasonLine.put("variable", reasonInfo);
            previewLines.add(reasonLine);
            return false;
        } else if (publishOnlyComplete && status != null
                && !status.equals(BookVersionsConstants.PAGESTATUS_PROP_STATUS_COMPLETE)) {
            // Page doesn't have a "complete" status but only those are published
            reasonInfo.put("pageRef", pageReference);
            reasonInfo.put("reason", "incomplete_status");
            reasonInfo.put("status", status);
            reasonInfo.put("requiredStatus", BookVersionsConstants.PAGESTATUS_PROP_STATUS_COMPLETE);

            reasonLine.put("message", "Page has status " + status + " but only COMPLETE pages are being published");
            reasonLine.put("variable", reasonInfo);
            previewLines.add(reasonLine);
            return false;
        } else if (variantReference == null && variants != null && !variants.isEmpty()) {
            // No variant to be published AND page is associated with variant(s)
            reasonInfo.put("pageRef", pageReference);
            reasonInfo.put("reason", "variant_page_no_variant_selected");
            reasonInfo.put("associatedVariants", variants);

            reasonLine.put("message", "Page is associated with variants but no variant is being published");
            reasonLine.put("variable", reasonInfo);
            previewLines.add(reasonLine);
            return false;
        } else if (variantReference != null && variants != null && !variants.contains(variantReference)
                && (excludePagesOutsideVariant || (!excludePagesOutsideVariant && !variants.isEmpty()))) {
            // A variant is to be published AND the page is associated with other variant(s)
            reasonInfo.put("pageRef", pageReference);
            reasonInfo.put("reason", "wrong_variant");
            reasonInfo.put("publishedVariant", variantReference);
            reasonInfo.put("associatedVariants", variants);
            reasonInfo.put("excludePagesOutsideVariant", excludePagesOutsideVariant);

            reasonLine.put("message", "Page is not associated with the published variant");
            reasonLine.put("variable", reasonInfo);
            previewLines.add(reasonLine);
            return false;
        } else if (StringUtils.isNotEmpty(language)) {
            Map<String, Map<String, Object>> languageData = getLanguageData(contentPageReference);
            if (languageData.get(language) == null) {
                // The page has no translation
                reasonInfo.put("pageRef", pageReference);
                reasonInfo.put("reason", "no_translation");
                reasonInfo.put("language", language);
                reasonInfo.put("availableLanguages", languageData.keySet());

                reasonLine.put("message", "Page has no translation for language");
                reasonLine.put("variable", reasonInfo);
                previewLines.add(reasonLine);
                return false;
            } else if (languageData.get(language).get(BookVersionsConstants.PAGETRANSLATION_HASTRANSLATED) == null
                    || !((boolean) languageData.get(language).get(BookVersionsConstants.PAGETRANSLATION_HASTRANSLATED))) {
                // The page has no "Translated" translation
                reasonInfo.put("pageRef", pageReference);
                reasonInfo.put("reason", "not_translated_status");
                reasonInfo.put("language", language);
                reasonInfo.put("translationStatus", languageData.get(language).get(BookVersionsConstants.PAGETRANSLATION_STATUS));
                reasonInfo.put("requiredStatus", PageTranslationStatus.TRANSLATED);

                reasonLine.put("message", "Page's translation doesn't have TRANSLATED status");
                reasonLine.put("variable", reasonInfo);
                previewLines.add(reasonLine);
                return false;
            }
        }

        // If we reach here, the page will be published - add detailed info about why it's being published
        Map<String, Object> publishReasonLine = new HashMap<>();
        Map<String, Object> publishReasonInfo = new HashMap<>();

        publishReasonInfo.put("pageRef", pageReference);
        publishReasonInfo.put("status", status);

        if (variantReference != null) {
            publishReasonInfo.put("variant", variantReference);
            publishReasonInfo.put("associatedVariants", variants);
        }

        if (StringUtils.isNotEmpty(language)) {
            Map<String, Map<String, Object>> languageData = getLanguageData(contentPageReference);
            publishReasonInfo.put("language", language);
            publishReasonInfo.put("translationStatus", languageData.get(language).get(BookVersionsConstants.PAGETRANSLATION_STATUS));
        }

        publishReasonLine.put("message", "Page meets all publication criteria");
        publishReasonLine.put("variable", publishReasonInfo);
        previewLines.add(publishReasonLine);

        return true;
    }

    @Override
    public void publishInternal(DocumentReference configurationReference, DocumentReference userDocumentReference,
        Locale userLocale)
        throws XWikiException, QueryException, ComponentLookupException, ParseException
    {
        if (configurationReference == null || userDocumentReference == null) {
            logger.error(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.publishInternal."
                + "nullParameter", userLocale));
            return;
        }
        if (userLocale == null) {
            userLocale = new Locale(BookVersionsConstants.DEFAULT_LOCALE);
        }

        logger.debug("[publishInternal] Publication required with configuration [{}]", configurationReference);
        logger.info(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.publishInternal.start",
            userLocale, configurationReference));
        XWikiContext xcontext = this.getXWikiContext();
        XWiki xwiki = xcontext.getWiki();

        // Load Publication
        logger.info(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.publishInternal"
                + ".loadConfiguration", userLocale));
        Map<String, Object> configuration = loadPublicationConfiguration(configurationReference);
        if (configuration == null || configuration.isEmpty()) {
            logger.error(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.publishInternal."
                    + "noConfig", userLocale));
            return;
        }

        DocumentReference sourceReference =
            (DocumentReference) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_SOURCE);
        if (sourceReference == null || !xwiki.exists(sourceReference, xcontext)) {
            logger.error(localization.getTranslationPlain(
                "BookVersions.DefaultBookVersionsManager.publishInternal." + "sourceNotExist", userLocale,
                sourceReference));
            return;
        }
        SpaceReference targetReference =
            (SpaceReference) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_DESTINATIONSPACE);
        if (targetReference == null) {
            logger.error(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.publishInternal."
                + "noTarget", userLocale, configurationReference));
            return;
        }

        String publicationBehaviour =
            (String) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_PUBLISHBEHAVIOUR);
        if (publicationBehaviour == null) {
            logger.error(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.publishInternal."
                + "noBehaviour", userLocale, configurationReference));
            return;
        }
        String language = (String) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_LANGUAGE);
        DocumentReference targetDocumentReference =
            new DocumentReference(new EntityReference(xwiki.DEFAULT_SPACE_HOMEPAGE,
                EntityType.DOCUMENT, targetReference));
        List<String> subTargetDocumentsString = queryPages(targetDocumentReference);
        if (publicationBehaviour.equals(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_PUBLISHBEHAVIOUR_CANCEL)
            && !isEmptyTargetSpace(subTargetDocumentsString, targetDocumentReference))
        {
            logger.info(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.publishInternal."
                + "targetNotEmpty", userLocale, targetReference));
            return;
        } else if (publicationBehaviour.equals(
            BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_PUBLISHBEHAVIOUR_REPUBLISH))
        {
            // Clear the destination space
            logger.info(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.publishInternal."
                + "targetEmptying", userLocale, targetReference));
            if (xwiki.exists(targetDocumentReference, xcontext)) {
                // also removing the top page
                subTargetDocumentsString.add(localSerializer.serialize(targetDocumentReference));
            }
            logger.debug("[publishInternal] Emptying the destination space, removing [{}] from wiki [{}].",
                subTargetDocumentsString, targetDocumentReference.getWikiReference());
            removeDocuments(subTargetDocumentsString, targetDocumentReference, userDocumentReference);
        }

        DocumentReference collectionReference = getVersionedCollectionReference(sourceReference);
        XWikiDocument collection =
            collectionReference != null ? xwiki.getDocument(collectionReference, xcontext) : null;
        DocumentReference versionReference =
            (DocumentReference) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_VERSION);
        XWikiDocument version = versionReference != null ? xwiki.getDocument(versionReference, xcontext) : null;

        String publicationComment = collection != null && version != null ? publicationComment =
            "Published from [" + collection.getTitle() + "], version [" + version.getTitle() + "]." : null;

        DocumentReference variantReference =
            (DocumentReference) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_VARIANT);
        XWikiDocument variant = variantReference != null ? xwiki.getDocument(variantReference, xcontext) : null;

        String targetTitle = (String) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_TITLE);

        Map<String, Map<DocumentReference, DocumentReference>> publishedLibraries =
            collection != null && versionReference != null && isBook(collectionReference)
                ? getUsedPublishedLibrariesWithInheritance(collection.getDocumentReference(), versionReference) : null;

        UserReference userReference = userReferenceResolver.resolve(userDocumentReference);

        // Execute publication job
        logger.info(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.publishInternal"
                + ".startPublication", userLocale));
        List<String> pageReferenceTree = getPageReferenceTree(sourceReference);
        List<DocumentReference> markedAsDeletedReferences = new ArrayList<>();
        int i = 1;
        int pageQuantity = pageReferenceTree != null ? pageReferenceTree.size() : 0;
        progressManager.pushLevelProgress(pageQuantity, this);
        for (String pageStringReference : pageReferenceTree) {
            if (pageStringReference == null) {
                logger.debug("[publishInternal] Page publication cancelled because the page reference is null.");
                logger.error(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.publishInternal"
                    + ".refNotFound", userLocale));
                continue;
            }

            progressManager.startStep(this, pageStringReference);
            logger.info(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.publishInternal"
                + ".startPagePublication", userLocale, i, pageQuantity, pageStringReference));
            i++;
            DocumentReference pageReference = referenceResolver.resolve(pageStringReference, configurationReference);

            if (!isPage(pageReference)) {
                logger.debug("[publishInternal] Page does not have a [{}] object.",
                    BookVersionsConstants.BOOKPAGE_CLASS_REFERENCE);
                logger.error(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.publishInternal"
                    + ".notCollection", userLocale));
                continue;
            }
            XWikiDocument page = xwiki.getDocument(pageReference, xcontext);

            // Get the relevant content for the page
            DocumentReference contentPageReference = getContentPage(page, configuration);
            logger.debug("[publishInternal] For page [{}], the content will be taken from [{}]",
                page.getDocumentReference(), contentPageReference);
            if (contentPageReference == null) {
                logger.debug("[publishInternal] Page publication cancelled because the content to be published can't "
                    + "be found by getContentPage. One input is probably null.");
                logger.warn(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.publishInternal"
                    + ".noContent", userLocale));
                continue;
            }

            // Get the published reference
            DocumentReference publishedReference =
                getPublishedReference(pageReference, sourceReference, targetReference);
            if (publishedReference == null) {
                logger.debug("[publishInternal] Page publication cancelled because the published reference can't be "
                    + "computed by getPublishedReference. One input is null.");
                logger.error(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.publishInternal"
                    + ".targetRefNotComputed", userLocale));
                continue;
            }

            // Check if the content should be published
            XWikiDocument contentPage = xwiki.getDocument(contentPageReference, xcontext).clone();
            if (!isToBePublished(contentPageReference, variant, configuration, userLocale)) {
                if (isMarkedDeleted(contentPageReference)) {
                    // The original document is marked as deleted, add the published copy to be deleted from target
                    markedAsDeletedReferences.add(publishedReference);
                }
                continue;
            }

            // Create the published document
            logger.info(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.publishInternal"
                + ".copyPage", userLocale, contentPage.getDocumentReference(), publishedReference));
            XWikiDocument publishedDocument = xwiki.getDocument(publishedReference, xcontext);
            if (StringUtils.isNotEmpty(language)) {
                // Change the original content if a translation is to be published
                mergeTranslatedContent(contentPage, publishedDocument, language);
            }
            copyContentsToNewVersion(contentPage, publishedDocument, xcontext,
                getRemovedObjectsForPublication(contentPage.getDocumentReference(), xcontext));

            logger.info(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.publishInternal"
                + ".transformContent", userLocale));
            prepareForPublication(sourceReference, contentPage, publishedDocument, publishedLibraries, configuration, userLocale);

            logger.debug("[publishInternal] Publish page.");
            publishedDocument.getAuthors().setEffectiveMetadataAuthor(userReference);
            publishedDocument.getAuthors().setOriginalMetadataAuthor(userReference);
            if (pageReference.equals(sourceReference) && StringUtils.isNotEmpty(targetTitle)) {
                publishedDocument.setTitle(targetTitle);
            }
            xwiki.saveDocument(publishedDocument, publicationComment, xcontext);
            logger.debug("[publishInternal] End working on page [{}].", pageStringReference);
            logger.info(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.publishInternal"
                + ".endPagePublication", userLocale, pageStringReference));
            progressManager.endStep(this);
        }

        // Remove the pages marked as deleted
        if (publicationBehaviour.equals(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_PUBLISHBEHAVIOUR_UPDATE)
            && markedAsDeletedReferences.size() > 0)
        {
            logger.info(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.publishInternal"
                + ".removeMarkedAsDeleted", userLocale));
            logger.debug("[publishInternal] Removing the following marked as deleted pages [{}].",
                markedAsDeletedReferences);
            removeDocuments(markedAsDeletedReferences, userDocumentReference);
        }

        if ((boolean) configuration.get("publishPageOrder")) {
            logger.info(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.publishInternal"
                + ".updatePageOrder", userLocale));
            copyPinnedPagesInfo(sourceReference, targetReference, publicationComment,
                configurationReference, userReference);
        }

        // Add metadata in the collection page (master) and top page (published space)
        logger.debug("[publishInternal] Adding metadata on master and published space top pages.");
        addMasterPublicationData(collection, configuration, userReference);
        addTopPublicationData(targetReference, publicationComment, collection, configuration, userReference,
            collectionReference);

        logger.debug("[publishInternal] Publication ended.");
        logger.info(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.publishInternal"
            + ".endPublication", userLocale, targetDocumentReference));
        progressManager.popLevelProgress(this);
    }

    @Override
    public String removeVersionContent(DocumentReference versionReference) throws JobException
    {
        if (versionReference == null) {
            return null;
        }

        DefaultRequest jobRequest = new DefaultRequest();
        String jobId = BookVersionsConstants.VERSIONCONTENTREMOVE_JOBID_PREFIX
            + BookVersionsConstants.PUBLICATION_JOBID_SEPARATOR + versionReference.getName()
            + BookVersionsConstants.PUBLICATION_JOBID_SEPARATOR + Instant.now().toString();
        if (jobId != null) {
            jobRequest.setId(jobId);
            jobRequest.setProperty("versionReference", versionReference);
            // The context won't be full in deleteVersionContentInternal as it is executed by a job, so the user
            // executing the deletion have to be passed as parameter.
            XWikiContext xcontext = this.getXWikiContext();
            jobRequest.setProperty("userReference", xcontext.getUserReference());
            jobExecutor.execute(BookVersionsConstants.VERSIONCONTENTREMOVEJOB_TYPE, jobRequest);
        }
        return jobId;
    }

    @Override
    public void removeVersionContentInternal(DocumentReference versionReference, DocumentReference userReference)
        throws QueryException, XWikiException
    {
        if (versionReference == null || userReference == null) {
            return;
        }

        DocumentReference collectionReference = getVersionedCollectionReference(versionReference);
        String versionName = getVersionName(versionReference);
        List<String> contentPageRefStrings =
            queryPages(collectionReference, BookVersionsConstants.BOOKVERSIONEDCONTENT_CLASS_REFERENCE);
        List<DocumentReference> versionContentPageRefs = new ArrayList<>();
        for (String contentPageRefString : contentPageRefStrings) {
            DocumentReference contentPageRef = referenceResolver.resolve(contentPageRefString, collectionReference);
            if (versionName.equals(getEscapedName(contentPageRef))) {
                versionContentPageRefs.add(contentPageRef);
            }
        }
        removeDocuments(versionContentPageRefs, userReference);
    }


    /**
     * Remove the given documents
     *
     * @param documentReferencesString the documents to remove
     * @param referenceParameter the reference used as a parameter to resolve the document references
     * @param userReference the reference of the user who removes the documents
     * @throws XWikiException happens if there is an issue with the document deletion
     */
    private void removeDocuments(List<String> documentReferencesString, DocumentReference referenceParameter,
        DocumentReference userReference)
        throws XWikiException
    {
        if (documentReferencesString == null || referenceParameter == null) {
            return;
        }
        List<DocumentReference> toDeleteReferences = new ArrayList<>();
        for (String toDeleteRefString : documentReferencesString) {
            toDeleteReferences.add(referenceResolver.resolve(toDeleteRefString, referenceParameter));
        }
        removeDocuments(toDeleteReferences, userReference);
    }

    /**
     * Remove the given documents
     *
     * @param documentReferences the documents to remove
     * @param userReference the reference of the user who removes the documents
     * @throws XWikiException happens if there is an issue with the document deletion or checking its existence
     */
    private void removeDocuments(List<DocumentReference> documentReferences, DocumentReference userReference)
        throws XWikiException
    {
        if (documentReferences == null) {
            return;
        }
        XWikiContext xcontext = this.getXWikiContext();
        XWiki xwiki = xcontext.getWiki();

        for (DocumentReference toDeleteRef : documentReferences) {
            if (xwiki.exists(toDeleteRef, xcontext)) {
                logger.debug("[removeDocuments] Deleting [{}].", toDeleteRef);
                xcontext.setUserReference(userReference);
                xwiki.deleteDocument(xwiki.getDocument(toDeleteRef, xcontext), xcontext);
            }
        }
    }

    /**
     * Merge the new document's translated content with the current published document's content. This has some
     * limitations:
     * - only the first "Translated" macro of the right language will be considered
     * - the content outside the macro will be ignored
     * - New content will only be added to the end of the published document
     * @param masterDocument the document to take the translated content from
     * @param publishedDocument the document to which the translated content has to be merged into
     * @param language the language to publish
     * @throws XWikiException happens if checking if document exists or setting the XDOM content have an issue
     */
    private void mergeTranslatedContent(XWikiDocument masterDocument, XWikiDocument publishedDocument, String language)
        throws XWikiException
    {
        if (masterDocument == null || publishedDocument == null || language == null) {
            logger.error("[mergeTranslatedContent] A parameter is null: masterDocument [{}], publishedDocument [{}], "
                    + "language [{}]", masterDocument.getDocumentReference(), publishedDocument.getDocumentReference(),
                language);
            return;
        }

        XWikiContext xcontext = this.getXWikiContext();
        XWiki xwiki = xcontext.getWiki();
        // Look for the content to be published
        logger.debug("[mergeTranslatedContent] Searching for the translated content of [{}] language in the "
            + "content to be published.", language);
        XDOM masterXdom = masterDocument.getXDOM();
        MacroBlock masterBlock = getTranslatedMacroBlock(masterXdom, language);
        if (masterBlock == null) {
            logger.debug("[mergeTranslatedContent] No [{}] translated content to be published found.", language);
            return;
        }

        // Search if some translated content has to be replaced in the currently published document
        Block toPublishBlock = null;
        if (xwiki.exists(publishedDocument.getDocumentReference(), xcontext)) {
            logger.debug("[mergeTranslatedContent] Searching for existing translated content of [{}] in already "
                + "existing published document.", language);
            XDOM publishedXdom = publishedDocument.getXDOM();
            MacroBlock publishedBlock = getTranslatedMacroBlock(publishedXdom, language);
            if (publishedBlock == null) {
                logger.debug("[mergeTranslatedContent] No existing translated published content, adding translated "
                    + "content macro at the end of the published of the published document.");
                publishedXdom.addChild(masterBlock);
                toPublishBlock = publishedXdom;
            } else {
                logger.debug("[mergeTranslatedContent] Found existing published content, replacing it by the new the "
                    + "content to be published.");
                publishedXdom.replaceChild(masterBlock, publishedBlock);
                toPublishBlock = publishedXdom;
            }
        } else {
            logger.debug("[mergeTranslatedContent] No existing published content, adding macro as new document.");
            toPublishBlock = masterBlock;
        }
        masterDocument.setContent(new XDOM(Collections.singletonList(toPublishBlock)));
    }

    /**
     * Get the first XDOM block of content translation macro for the given language, with the status "Translated", in
     * the given document
     *
     * @param xdom the xdom of the document to get the macro block from
     * @param language the language of the macro
     * @return the macro block found, or null if none
     */
    private MacroBlock getTranslatedMacroBlock(XDOM xdom, String language)
    {
        if (xdom == null || language == null) {
            logger.error("[mergeTranslatedContent] A parameter is null: xdom [{}], language [{}]", xdom,
                language);
            return null;
        }

        logger.debug("[getTranslatedMacroBlock] Looking for the first [{}] macro, with the [{}] status, for language "
                + "[{}].", BookVersionsConstants.CONTENTTRANSLATION_MACRO_ID,
            PageTranslationStatus.TRANSLATED, language);
        List<MacroBlock> listBlock = xdom.getBlocks(new ClassBlockMatcher(
            new MacroBlock(BookVersionsConstants.CONTENTTRANSLATION_MACRO_ID, Collections.emptyMap(),
                true).getClass()), Block.Axes.DESCENDANT_OR_SELF);
        logger.debug("[getTranslatedMacroBlock] Found [{}] [{}] macros", listBlock.size(),
            BookVersionsConstants.CONTENTTRANSLATION_MACRO_ID);
        for (MacroBlock macroBlock : listBlock) {
            String macroLanguage = macroBlock.getParameter(BookVersionsConstants.PAGETRANSLATION_LANGUAGE);
            if (macroLanguage != null && !macroLanguage.isEmpty()) {
                if (macroLanguage.equals(language)) {
                    logger.debug("[getTranslatedMacroBlock] Macro language is [{}].", macroLanguage);
                    String macroStatus = macroBlock.getParameter(BookVersionsConstants.PAGETRANSLATION_STATUS);
                    if (StringUtils.isNotEmpty(macroStatus)
                        && macroStatus.toLowerCase().equals(PageTranslationStatus.TRANSLATED.getTranslationStatus()))
                    {
                        logger.debug("[getTranslatedMacroBlock] Macro status is [{}]. Returning this one.",
                            macroStatus);
                        return macroBlock;
                    } else {
                        logger.debug("[getTranslatedMacroBlock] Macro status is [{}]. Continue searching.",
                            macroStatus);
                    }
                } else {
                    logger.debug("[getTranslatedMacroBlock] Macro language is [{}]. Continue searching.",
                        macroLanguage);
                }
            }
        }
        logger.debug("[getTranslatedMacroBlock] No [{}] content found for language [{}].",
            BookVersionsConstants.PAGETRANSLATION_STATUS, language);
        return null;
    }

    /**
     * Return if the given space is empty, except for his WebHome and WebPreferences pages
     *
     * @param subTargetDocumentsString list of pages under the target space
     * @param targetDocumentReference WebHome document of the target space
     * @return true if space is empty, except for his WebHome and WebPreferences pages. False if any parameter is null
     */
    private boolean isEmptyTargetSpace(List<String> subTargetDocumentsString, DocumentReference targetDocumentReference)
    {
        if (subTargetDocumentsString == null || targetDocumentReference == null) {
            return false;
        }
        subTargetDocumentsString.remove(localSerializer.serialize(targetDocumentReference));
        subTargetDocumentsString.remove(localSerializer.serialize(
            new DocumentReference(new EntityReference(BookVersionsConstants.XWIKI_PAGEADMINISTRATION_NAME,
                EntityType.DOCUMENT, targetDocumentReference.getLastSpaceReference()))));
        return subTargetDocumentsString.isEmpty();
    }

    /**
     * Copy a document to another document, and remove the provided objects. The target document won't be saved.
     * Adapted from org.xwiki.workflowpublication.internal.DefaultPublicationWorkflow (Publication Workflow Application)
     *
     * @param fromDocument the document to copy
     * @param toDocument the target document. It won't be saved, so it will need to be done after this method call.
     * @param xcontext the context
     * @param removedObjects the objects to be removed during copy
     * @return true if the copy worked
     * @throws XWikiException happens if loading attachments or removing the objects have issue.
     */
    protected boolean copyContentsToNewVersion(XWikiDocument fromDocument, XWikiDocument toDocument,
        XWikiContext xcontext, List<EntityReference> removedObjects) throws XWikiException
    {
        if (fromDocument == null || toDocument == null || xcontext == null) {
            return false;
        }

        // use a fake 3 way merge: previous is toDocument without the provided list of objects
        // current version is current toDocument
        // next version is fromDocument without the provided list of objects
        XWikiDocument previousDoc = toDocument.clone();
        this.removeObjects(previousDoc, removedObjects);
        // set reference and language

        // make sure that the attachments are properly loaded in memory for the duplicate to work fine, otherwise it's a
        // bit impredictable about attachments
        fromDocument.loadAttachments(xcontext);
        XWikiDocument nextDoc = fromDocument.duplicate(toDocument.getDocumentReference());
        this.removeObjects(nextDoc, removedObjects);

        // and now merge. Normally the attachments which are not in the next doc are deleted from the current doc
        MergeResult result = toDocument.merge(previousDoc, nextDoc, new MergeConfiguration(), xcontext);

        // for some reason the creator doesn't seem to be copied if the toDocument is new, so let's put it
        if (toDocument.isNew()) {
            toDocument.setCreatorReference(fromDocument.getCreatorReference());
        }
        // Author does not seem to be merged anymore in the merge function in newer versions, so we'll do it here
        toDocument.setAuthorReference(fromDocument.getAuthorReference());

        List<LogEvent> exception = result.getLog().getLogs(LogLevel.ERROR);
        if (exception.isEmpty()) {
            return true;
        } else {
            StringBuffer exceptions = new StringBuffer();
            for (LogEvent e : exception) {
                if (exceptions.length() == 0) {
                    exceptions.append(";");
                }
                exceptions.append(e.getMessage());
            }
            throw new XWikiException(XWikiException.MODULE_XWIKI_DOC, XWikiException.ERROR_XWIKI_UNKNOWN,
                "Could not copy document contents from "
                    + localSerializer.serialize(fromDocument.getDocumentReference()) + " to document "
                    + localSerializer.serialize(toDocument.getDocumentReference()) + ". Caused by: "
                    + exceptions.toString());
        }
    }

    private void addTopPublicationData(SpaceReference targetTopReference, String publicationComment,
        XWikiDocument collection, Map<String, Object> configuration,
        UserReference userReference, DocumentReference collectionReference) throws XWikiException
    {
        if (targetTopReference == null || collection == null || configuration == null) {
            return;
        }

        logger.debug("[addTopPublicationData] Adding metadata to [{}]", targetTopReference);
        XWikiContext xcontext = this.getXWikiContext();
        XWiki xwiki = xcontext.getWiki();
        DocumentReference versionReference =
            (DocumentReference) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_VERSION);
        XWikiDocument version = xwiki.getDocument(versionReference, xcontext);
        DocumentReference variantReference =
            (DocumentReference) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_VARIANT);

        // Set metadata
        DocumentReference targetDocumentReference =
            new DocumentReference(new EntityReference(xwiki.DEFAULT_SPACE_HOMEPAGE, EntityType.DOCUMENT,
                targetTopReference));
        XWikiDocument targetTop = xwiki.getDocument(targetDocumentReference, xcontext).clone();
        BaseObject publicationObject =
            targetTop.getXObject(BookVersionsConstants.PUBLISHEDCOLLECTION_CLASS_REFERENCE);
        if (publicationObject == null) {
            publicationObject =
                targetTop.newXObject(BookVersionsConstants.PUBLISHEDCOLLECTION_CLASS_REFERENCE, xcontext);
        }
        publicationObject.set(BookVersionsConstants.PUBLISHEDCOLLECTION_PROP_MASTERNAME, collection.getTitle(),
            xcontext);
        publicationObject.set(BookVersionsConstants.PUBLISHEDCOLLECTION_PROP_VERSIONNAME, version.getTitle(), xcontext);
        List languages = publicationObject.getListValue(BookVersionsConstants.PUBLISHEDCOLLECTION_PROP_LANGUAGES);
        String publicationLanguage =
            (String) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_LANGUAGE);
        if (StringUtils.isNotEmpty(publicationLanguage)) {
            if (!languages.contains(publicationLanguage)) {
                languages.add(publicationLanguage);
            }
        } else {
            languages = getConfiguredLanguages(collectionReference);
        }
        publicationObject.set(BookVersionsConstants.PUBLISHEDCOLLECTION_PROP_LANGUAGES, languages, xcontext);
        if (variantReference != null) {
            XWikiDocument variant = xwiki.getDocument(variantReference, xcontext);
            publicationObject.set(BookVersionsConstants.PUBLISHEDCOLLECTION_PROP_VARIANTNAME, variant.getTitle(),
                xcontext);
        }
        targetTop.getAuthors().setEffectiveMetadataAuthor(userReference);
        targetTop.getAuthors().setOriginalMetadataAuthor(userReference);
        xwiki.saveDocument(targetTop, publicationComment != null ? publicationComment : "", xcontext);
    }

    private void addMasterPublicationData(XWikiDocument collection, Map<String, Object> configuration,
        UserReference userReference) throws XWikiException
    {
        if (collection == null || configuration == null) {
            return;
        }

        logger.debug("[addMasterPublicationData] Adding metadata to [{}]", collection.getDocumentReference());
        XWikiContext xcontext = this.getXWikiContext();
        XWiki xwiki = xcontext.getWiki();
        DocumentReference sourceReference =
            (DocumentReference) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_SOURCE);
        SpaceReference destinationSpaceReference =
            (SpaceReference) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_DESTINATIONSPACE);
        DocumentReference destinationReference =
            new DocumentReference(new EntityReference(xwiki.DEFAULT_SPACE_HOMEPAGE,
                EntityType.DOCUMENT, destinationSpaceReference));
        DocumentReference versionReference =
            (DocumentReference) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_VERSION);
        XWikiDocument version = versionReference != null ? xwiki.getDocument(versionReference, xcontext) : null;
        DocumentReference variantReference =
            (DocumentReference) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_VARIANT);
        XWikiDocument collectionClone = collection.clone();

        if (sourceReference == null || versionReference == null || version == null) {
            logger.error(
                "Couldn't read the publication configuration when attempting to add metadata in the master and "
                    + "published locations.");
            return;
        }

        String publicationId;
        String publicationComment;
        if (variantReference == null) {
            publicationId = versionReference.getName();
            publicationComment =
                "Publication of space [" + sourceReference + "], version [" + version.getTitle() + "].";
        } else {
            XWikiDocument variant = xwiki.getDocument(variantReference, xcontext);
            publicationId = versionReference.getName() + "-" + variantReference.getName();
            publicationComment = "Publication of space [" + sourceReference + "], version [" + version.getTitle()
                + "], variant [" + variant.getTitle() + "].";
        }

        // Check existing publication objects
        logger.debug("[addMasterPublicationData] Checking if an object already exists for ID [{}] and source [{}].",
            publicationId, sourceReference);
        BaseObject publicationObject = null;
        for (BaseObject XObject : collectionClone.getXObjects(BookVersionsConstants.PUBLICATION_CLASS_REFERENCE)) {
            if (XObject == null) {
                continue;
            }
            String objectId = XObject.getStringValue(BookVersionsConstants.PUBLICATION_PROP_ID);
            String objectSource = XObject.getStringValue(BookVersionsConstants.PUBLICATION_PROP_SOURCE);
            if (publicationId != null && publicationId.equals(objectId) && objectSource != null
                && sourceReference.toString().equals(objectSource))
            {
                // Previous publication of the same space, with same version and variant found
                logger.debug("[addMasterPublicationData] An object already exists.");
                publicationObject = XObject;
                break;
            }
        }
        if (publicationObject == null) {
            // No previous publication found, create a new one
            logger.debug("[addMasterPublicationData] A new object is added.");
            publicationObject = collectionClone.newXObject(BookVersionsConstants.PUBLICATION_CLASS_REFERENCE, xcontext);
        }

        // Add metadata
        publicationObject.set(BookVersionsConstants.PUBLICATION_PROP_ID, publicationId, xcontext);
        publicationObject.set(BookVersionsConstants.PUBLICATION_PROP_SOURCE, sourceReference.toString(), xcontext);
        publicationObject.set(BookVersionsConstants.PUBLICATION_PROP_PUBLISHEDSPACE, destinationReference.toString(),
            xcontext);
        collectionClone.getAuthors().setEffectiveMetadataAuthor(userReference);
        collectionClone.getAuthors().setOriginalMetadataAuthor(userReference);
        xwiki.saveDocument(collectionClone, publicationComment, xcontext);
    }

    /**
     * Get the published space of a given collection for a given publication ID and source.
     *
     * @param collectionReference the collection
     * @param publicationId the publication ID
     * @param sourceReference the source
     * @return the reference of the published space
     * @throws XWikiException
     */
    private DocumentReference getCollectionPublishedSpace(DocumentReference collectionReference, String publicationId,
        DocumentReference sourceReference) throws XWikiException
    {
        if (publicationId == null || collectionReference == null || sourceReference == null
            || (!isBook(collectionReference) && !isLibrary(collectionReference)))
        {
            return null;
        }

        logger.debug("[getCollectionPublishedSpace] Search the published space for collection [{}], id [{}] and "
            + "source [{}].", collectionReference, publicationId, sourceReference);
        XWikiContext xcontext = this.getXWikiContext();
        XWiki xwiki = xcontext.getWiki();
        XWikiDocument collection = xwiki.getDocument(collectionReference, xcontext);
        DocumentReference publishedSpaceReference = null;
        for (BaseObject XObject : collection.getXObjects(BookVersionsConstants.PUBLICATION_CLASS_REFERENCE)) {
            if (XObject == null) {
                continue;
            }
            String objectId = XObject.getStringValue(BookVersionsConstants.PUBLICATION_PROP_ID);
            String objectSource = XObject.getStringValue(BookVersionsConstants.PUBLICATION_PROP_SOURCE);
            if (objectId != null && publicationId.equals(objectId) && objectSource != null
                && sourceReference.toString().equals(objectSource))
            {
                publishedSpaceReference = referenceResolver
                    .resolve(XObject.getStringValue(BookVersionsConstants.PUBLICATION_PROP_PUBLISHEDSPACE));
                logger.debug("[getCollectionPublishedSpace] The collection was published in [{}]",
                    publishedSpaceReference);
                break;
            }
        }

        return publishedSpaceReference;
    }

    /**
     * Replaces the collectionReference by the targetReference in the originalReference, to get a published reference.
     *
     * @param originalReference the reference of the page to be published
     * @param collectionReference the reference of the collection which is published
     * @param targetReference the target space where the collection is published
     * @return the reference of the published page
     */
    private static DocumentReference getPublishedReference(DocumentReference originalReference,
        DocumentReference collectionReference, SpaceReference targetReference)
    {
        if (originalReference == null || collectionReference == null || targetReference == null) {
            return null;
        }

        DocumentReference publishedReference =
            originalReference.replaceParent(collectionReference.getParent(), targetReference);

        return publishedReference;
    }

    /**
     * Remove the objects from the document. The document won't be saved.
     *
     * @param document the document to remove the objects from. It won't be saved, so it will need to be after
     * calling this method.
     * @param removedObjects the list of objects to remove
     * @return the document after removing the objects.
     */
    private XWikiDocument removeObjects(XWikiDocument document, List<EntityReference> removedObjects)
    {
        if (document == null || removedObjects == null) {
            return null;
        }

        for (EntityReference objectRef : removedObjects ) {
            document.removeXObjects(objectRef);
        }

        return document;
    }

    /**
     * Get the list of objects to be removed at publication.
     *
     * @param documentReference the document which will be published reference
     * @param xcontext the context
     * @return the list of objects to be removed at publication
     * @throws XWikiException if issues while getting or checking the existence of the configuration document
     */
    private List<EntityReference> getRemovedObjectsForPublication(DocumentReference documentReference,
        XWikiContext xcontext) throws XWikiException
    {
        if (documentReference == null || xcontext == null) {
            return Collections.emptyList();
        }

        DocumentReference objectsToRemoveConfigRef = referenceEntityResolver.resolve(
            BookVersionsConstants.CONFIGURATION_REMOVEDOBJECTS, documentReference);
        List<EntityReference> removedObjects = new ArrayList<>(BookVersionsConstants.PUBLICATION_REMOVEDOBJECTS);
        removedObjects.addAll(getRemovedObjectsConfiguration(objectsToRemoveConfigRef, xcontext));
        return removedObjects;
    }

    /**
     * Get the objects to remove from the provided configuration document.
     *
     * @param objectsToRemoveConfigRef the reference of the document to get the objects from
     * @param xcontext the context
     * @return the list of objects contained in the document
     * @throws XWikiException if issues while getting the document or checking its existence
     */
    private List<EntityReference> getRemovedObjectsConfiguration(DocumentReference objectsToRemoveConfigRef,
        XWikiContext xcontext)
        throws XWikiException
    {
        if (objectsToRemoveConfigRef == null || xcontext == null) {
            return Collections.emptyList();
        }

        XWiki xwiki = xcontext.getWiki();
        if (!xwiki.exists(objectsToRemoveConfigRef, xcontext)) {
            logger.debug("[getRemovedObjectsConfiguration] Can't find configuration page [{}].",
                objectsToRemoveConfigRef);
            return Collections.emptyList();
        }

        XWikiDocument configDoc = xwiki.getDocument(objectsToRemoveConfigRef, xcontext);
        Map<DocumentReference, List<BaseObject>> xObjects = configDoc.getXObjects();
        List<EntityReference> result = new ArrayList<>();
        for (DocumentReference fullObjectRef : xObjects.keySet()) {
            if (fullObjectRef == null) {
                continue;
            }
            result.add(entityReferenceResolver.resolve(localSerializer.serialize(fullObjectRef), EntityType.DOCUMENT));
        }
        logger.debug("[getRemovedObjectsConfiguration] Configuration is [{}].", result);
        return result;
    }

    private XWikiDocument prepareForPublication(DocumentReference publicationSourceReference,
        XWikiDocument originalDocument, XWikiDocument publishedDocument,
        Map<String, Map<DocumentReference, DocumentReference>> publishedLibraries, Map<String, Object> configuration,
        Locale userLocale) throws XWikiException, ComponentLookupException, ParseException, QueryException
    {
        if (originalDocument == null || publishedDocument == null || configuration == null) {
            return null;
        }

        // Execute here all transformations on the document: change links, point to published library,
        logger.debug("[prepareForPublication] Apply changes on [{}] for publication.",
            publishedDocument.getDocumentReference());
        // Work directly on the document
        publishedDocument.setTitle(originalDocument.getTitle());
        publishedDocument.setHidden(false);
        // Work on the XDOM
        XDOM xdom = publishedDocument.getXDOM();
        String syntax = publishedDocument.getSyntax().toIdString();
        DocumentReference sourceCollectionReference = getVersionedCollectionReference(publicationSourceReference);
        transformXDOM(sourceCollectionReference, publicationSourceReference, xdom, syntax, originalDocument.getDocumentReference(),
            publishedLibraries, configuration, userLocale);
        // Set the modified XDOM
        publishedDocument.setContent(xdom);
        return publishedDocument;
    }

    // Heavily inspired from "Bulk update links according to a TSV mapping using XDOM" on https://snippets.xwiki.org
    private boolean transformXDOM(DocumentReference sourceCollectionReference,
        DocumentReference publicationSourceReference, XDOM xdom, String syntaxId,
        DocumentReference originalDocumentReference,
        Map<String, Map<DocumentReference, DocumentReference>> publishedLibraries, Map<String, Object> configuration,
        Locale userLocale) throws ComponentLookupException, ParseException, QueryException, XWikiException
    {
        if (xdom == null || syntaxId == null || originalDocumentReference == null || configuration == null) {
            logger.error("[transformXDOM] Can't execute because one input is null.");
            return false;
        }

        boolean hasXDOMChanged = false;
        DocumentReference publishedVariantReference =
            (DocumentReference) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_VARIANT);
        DocumentReference versionReference =
            (DocumentReference) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_VERSION);
        String language = (String) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_LANGUAGE);

        // First, update any document macro that could contain nested content (variant macro)

        for (Block b : xdom.getBlocks(new ClassBlockMatcher(MacroBlock.class), Block.Axes.DESCENDANT_OR_SELF)) {
            MacroBlock block = (MacroBlock) b;
            String id = block.getId();

            // Should never happen.
            if (id == null) {
                logger.error("[transformXDOM] Can't find macro block id.");
                continue;
            }

            String content = block.getContent();
            logger.debug("[transformXDOM] Checking macro [{}] - [{}]", id, block.getClass());

            // Get the possible variants from the variant macro
            String variants = block.getParameter(BookVersionsConstants.VARIANT_MACRO_PROP_NAME);
            List<DocumentReference> variantReferences = new ArrayList<>();
            if (variants != null) {
                for (String variant : variants.split(",")) {
                    variantReferences.add(referenceResolver.resolve(variant, originalDocumentReference));
                }
            }

            if (id.equals(BookVersionsConstants.VARIANT_MACRO_ID) && ((publishedVariantReference == null)
                || (publishedVariantReference != null && !variantReferences.contains(publishedVariantReference))))
            {
                // The macro is removed if it's a macro variant AND ((no variant is published)) OR (variant is
                // published but macro is not for the published variant)
                logger.debug("[transformXDOM] Variant macro is for [{}], it is removed from content.",
                    variantReferences);
                block.getParent().removeBlock(block);
            } else {
                if (content == null || StringUtils.isEmpty(content)) {
                    continue;
                }

                if (!shouldParseMacroContent(block)) {
                    continue;
                }

                // We will take a quick shortcut here and directly parse the macro content with the syntax of the
                // document
                logger.debug("[transformXDOM] Calling parse on [{}] with syntax [{}]", id, syntaxId);
                Parser parser = componentManagerProvider.get().getInstance(Parser.class, syntaxId);
                XDOM contentXDOM = parser.parse(new StringReader(content));
                boolean hasMacroContentChanged =
                    transformXDOM(sourceCollectionReference, publicationSourceReference, contentXDOM, syntaxId, originalDocumentReference, publishedLibraries, configuration
                        , userLocale);
                if (hasMacroContentChanged || id.equals(BookVersionsConstants.VARIANT_MACRO_ID)) {
                    logger.debug("[transformXDOM] The content of macro [{}] has changed", id);
                    if (id.equals(BookVersionsConstants.VARIANT_MACRO_ID)) {
                       logger.debug("[transformXDOM] Variant macro is for [{}], it is replaced by the {} macro.",
                           variantReferences, BookVersionsConstants.INLINE_MACRO_ID);
                    }
                    WikiPrinter printer = new DefaultWikiPrinter();
                    BlockRenderer renderer =
                        this.componentManagerProvider.get().getInstance(BlockRenderer.class, syntaxId);
                    renderer.render(contentXDOM, printer);
                    String newMacroContent = printer.toString();
                    // Create a new macro block and swap it
                    MacroBlock newMacroBlock =
                        new MacroBlock(id, block.getParameters(), newMacroContent, block.isInline());
                    if (id.equals(BookVersionsConstants.VARIANT_MACRO_ID)) {
                        newMacroBlock = new MacroBlock(BookVersionsConstants.INLINE_MACRO_ID, new HashMap<>(),
                        newMacroContent, block.isInline());
                    }
                    block.getParent().replaceChild(newMacroBlock, block);
                    hasXDOMChanged = true;
                }
            }
        }

        boolean transformedTranslation = transformTranslation(xdom, language);
        boolean transformedLibrary =
            transformLibrary(xdom, originalDocumentReference, publishedLibraries, versionReference,
                BookVersionsConstants.INCLUDELIBRARY_MACRO_ID, userLocale);
        boolean transformedExceptLibrary =
            transformLibrary(xdom, originalDocumentReference, publishedLibraries, versionReference,
                BookVersionsConstants.EXCERPTINCLUDELIBRARY_MACRO_ID, userLocale);
        Map<DocumentReference, DocumentReference> currentPublishedLibraries =
            publishedLibraries != null ? publishedLibraries.get(getVersionName(versionReference)) : new HashMap<>();
        boolean transformSiblingBookPage =
            transformSiblingBookPage(xdom, originalDocumentReference, configuration, userLocale);
        boolean transformedReferences = publicationReferencesTransformationHelper.transform(sourceCollectionReference,
            publicationSourceReference, xdom, originalDocumentReference, currentPublishedLibraries, configuration);

        return hasXDOMChanged || transformedTranslation || transformedLibrary || transformedExceptLibrary
            || transformSiblingBookPage || transformedReferences;
    }

    private boolean shouldParseMacroContent(MacroBlock macroBlock) throws ComponentLookupException
    {
        ComponentManager componentManager = this.componentManagerProvider.get();
        if (!componentManager.hasComponent(Macro.class, macroBlock.getId())) {
            return false;
        }

        Macro<?> macro = componentManager.getInstance(Macro.class, macroBlock.getId());
        ContentDescriptor contentDescriptor = macro.getDescriptor().getContentDescriptor();
        return contentDescriptor != null && Block.LIST_BLOCK_TYPE.equals(contentDescriptor.getType());
    }

    private boolean transformTranslation(XDOM xdom, String language)
    {
        if (xdom == null) {
            return false;
        }
        logger.debug("[transformTranslation] Starting to remove macro [{}] with not a [{}] status.",
            BookVersionsConstants.CONTENTTRANSLATION_MACRO_ID, PageTranslationStatus.TRANSLATED);
        if (StringUtils.isNotEmpty(language)) {
            logger.debug("[transformTranslation] Removing canceled as only [{}] are part of the publication of "
                + "language [{}].", PageTranslationStatus.TRANSLATED, language);
        }

        boolean hasChanged = false;
        List<MacroBlock> listBlock = xdom.getBlocks(new ClassBlockMatcher(
            new MacroBlock(BookVersionsConstants.CONTENTTRANSLATION_MACRO_ID, Collections.emptyMap(),
                true).getClass()), Block.Axes.DESCENDANT_OR_SELF);
        logger.debug("[transformTranslation] Found [{}] [{}] macros.", listBlock.size(),
            BookVersionsConstants.CONTENTTRANSLATION_MACRO_ID);
        for (MacroBlock macroBlock : listBlock) {
            String macroStatus = macroBlock.getParameter(BookVersionsConstants.PAGETRANSLATION_STATUS);
            if (StringUtils.isNotEmpty(macroStatus)
                && !macroStatus.toLowerCase().equals(PageTranslationStatus.TRANSLATED.getTranslationStatus()))
            {
                logger.debug("[transformTranslation] Status is [{}], macro is removed.", macroStatus);
                macroBlock.getParent().removeBlock(macroBlock);
                hasChanged = true;
            } else {
                logger.debug("[transformTranslation] Status is [{}], macro is kept.", macroStatus);
            }
        }
        return hasChanged;
    }

    /**
     * Transform the includeLibrary or excerptIncludeLibrary macros to include or excerpt-include respectively.
     * @param xdom the XDOM to work on
     * @param originalDocumentReference the page from which the content is taken from
     * @param publishedLibraries the published libraries used in the book
     * @param versionReference the version selected for the publication
     * @param macroId the macro to work on, includeLibrary or excerptIncludeLibrary
     * @param userLocale the current locale of the user
     * @return true if the XDOM has been changed
     * @throws QueryException if an error happens when searching for the referenced library page
     * @throws XWikiException if an error happens when searching for the referenced library page or checking if
     *      originalDocumentReference is versioned content
     */
    private boolean transformLibrary(XDOM xdom, DocumentReference originalDocumentReference,
        Map<String, Map<DocumentReference, DocumentReference>> publishedLibraries, DocumentReference versionReference,
        String macroId, Locale userLocale)
        throws QueryException, XWikiException
    {
        if (xdom == null || originalDocumentReference == null || versionReference == null || macroId == null) {
            return false;
        }

        String referenceProperty = "";
        String replaceMacroId = "";
        if (macroId.equals(BookVersionsConstants.INCLUDELIBRARY_MACRO_ID)) {
            referenceProperty = BookVersionsConstants.INCLUDELIBRARY_MACRO_PROP_KEYREFERENCE;
            replaceMacroId = BookVersionsConstants.DISPLAY_MACRO_ID;
        } else if (macroId.equals(BookVersionsConstants.EXCERPTINCLUDELIBRARY_MACRO_ID)) {
            referenceProperty = BookVersionsConstants.EXCERPTINCLUDELIBRARY_MACRO_PROP_KEYREFERENCE;
            replaceMacroId = BookVersionsConstants.EXCERPTINCLUDE_MACRO_ID;
        } else {
            logger.error("[transformLibrary] [{}] is not an expected macro ID", macroId);
            return false;
        }

        logger.debug("[transformLibrary] Starting to transform [{}] macro reference", macroId);
        boolean hasChanged = false;
        List<MacroBlock> listBlock = xdom.getBlocks(
            new ClassBlockMatcher(
                new MacroBlock(macroId, Collections.emptyMap(), true).getClass()),
            Block.Axes.DESCENDANT_OR_SELF);
        logger.debug("[transformLibrary] [{}] '{}' macros found in the passed XDOM", listBlock.size(), macroId);

        String versionName = getVersionName(versionReference);
        String inheritedVersionName = null;
        if (isVersionedContent(originalDocumentReference)) {
            // If versioned page is published, use the inherited version, provided by the document to be published.
            // This allows to use library version corresponding to the inherited content version instead of the
            // selected version for publication.
            inheritedVersionName = originalDocumentReference.getName();
        }

        for (MacroBlock macroBlock : listBlock) {
            if (!macroId.equals(macroBlock.getId())) {
                continue;
            }
            // Get the key reference (library page reference)
            String keyRefString = macroBlock.getParameter(referenceProperty);
            if (keyRefString == null || StringUtils.isEmpty(keyRefString)) {
                logger.debug("[transformLibrary] {} macro found without {} parameter. Macro is ignored.", macroId,
                    referenceProperty);
                continue;
            }

            DocumentReference libraryPageReference = referenceResolver.resolve(keyRefString, originalDocumentReference);
            logger.debug("[transformLibrary] Updating {} macro referencing [{}].", macroId, libraryPageReference);
            // Get the library reference
            DocumentReference libraryReference = getVersionedCollectionReference(libraryPageReference);
            // Get the published library reference
            // If the library version was not published, then get the inherited published library reference
            DocumentReference publishedLibraryReference =
                publishedLibraries != null && versionName != null && libraryReference != null
                    ? publishedLibraries.get(versionName).get(libraryReference) : (inheritedVersionName != null
                        ? publishedLibraries.get(inheritedVersionName).get(libraryReference) : null);
            if (publishedLibraryReference == null) {
                logger.error(localization.getTranslationPlain(
                    "BookVersions.DefaultBookVersionsManager." + "transformLibrary.notPublished", userLocale,
                    libraryReference));
                continue;
            }

            // Compute the published page reference
            DocumentReference publishedPageReference =
                getPublishedReference(libraryPageReference, libraryReference,
                    publishedLibraryReference.getLastSpaceReference());
            if (publishedPageReference != null) {
                logger.debug("[transformLibrary] Page reference is changed to [{}], macro is changed to [{}].",
                    publishedPageReference, replaceMacroId);
                // Replace the macro change to the published reference
                Map<String, String> parametersMap = new HashMap<>();
                if (macroId.equals(BookVersionsConstants.INCLUDELIBRARY_MACRO_ID)) {
                    parametersMap = Map.of(
                        BookVersionsConstants.INCLUDE_MACRO_PROP_REFERENCE, publishedPageReference.toString());
                } else if (macroId.equals(BookVersionsConstants.EXCERPTINCLUDELIBRARY_MACRO_ID)) {
                    parametersMap = new HashMap<>(macroBlock.getParameters());
                    parametersMap.put(
                        BookVersionsConstants.EXCERPTINCLUDE_MACRO_PROP_0, publishedPageReference.toString());
                }
                MacroBlock newMacroBlock = new MacroBlock(replaceMacroId, parametersMap, macroBlock.isInline());
                macroBlock.getParent().replaceChild(newMacroBlock, macroBlock);
                hasChanged = true;
            }
        }

        return hasChanged;
    }

    private boolean transformSiblingBookPage(XDOM xdom, DocumentReference originalDocumentReference,
        Map<String, Object> configuration, Locale userLocale) throws QueryException, XWikiException
    {
        if (xdom == null || originalDocumentReference == null || configuration == null) {
            return false;
        }

        boolean hasChanged = false;
        List<MacroBlock> listBlock = xdom.getBlocks(new ClassBlockMatcher(
            new MacroBlock(BookVersionsConstants.INCLUDESIBLINGBOOKPAGE_MACRO_ID, Collections.emptyMap(), true)
                .getClass()),
            Block.Axes.DESCENDANT_OR_SELF);

        for (MacroBlock macroBlock : listBlock) {
            if (!BookVersionsConstants.INCLUDESIBLINGBOOKPAGE_MACRO_ID.equals(macroBlock.getId())) {
                continue;
            }
            // Get the reference
            String refString =
                macroBlock.getParameter(BookVersionsConstants.INCLUDESIBLINGBOOKPAGE_MACRO_PROP_REFERENCE);
            if (refString == null || StringUtils.isEmpty(refString)) {
                continue;
            }

            // Replace the macro by display macro and change to the published reference
            MacroBlock newMacroBlock = new MacroBlock(BookVersionsConstants.DISPLAY_MACRO_ID,
                Map.of(BookVersionsConstants.DISPLAY_MACRO_PROP_REFERENCE, refString), macroBlock.isInline());
            macroBlock.getParent().replaceChild(newMacroBlock, macroBlock);
            hasChanged = true;
        }

        return hasChanged;
    }

    private void copyPinnedPagesInfo(DocumentReference sourceReference, SpaceReference targetReference,
        String publicationComment, DocumentReference configurationReference, UserReference userReference)
        throws QueryException, XWikiException
    {
        XWikiContext xcontext = getXWikiContext();
        XWiki xwiki = xcontext.getWiki();

        SpaceReference spaceReference = sourceReference.getLastSpaceReference();
        String spaceSerialized = localSerializer.serialize(spaceReference);
        String hql = "SELECT doc.fullName FROM XWikiDocument as doc "
            + "WHERE doc.name = 'WebPreferences' "
            + "  AND doc.fullName LIKE :docFullNamePrefix";
        List<String> webPreferences = this.queryManagerProvider.get()
            .createQuery(hql, Query.HQL)
            .bindValue("docFullNamePrefix").like(spaceSerialized + ".").anyChars().like("WebPreferences").query()
            .setWiki(sourceReference.getWikiReference().getName())
            .execute();

        for (String source : webPreferences) {
            DocumentReference sourceRef = referenceResolver.resolve(source, configurationReference);
            DocumentReference publishedReference = getPublishedReference(sourceRef, sourceReference, targetReference);
            if (publishedReference == null) {
                logger.debug("[publishInternal] Ignore pinned page for [{}] because 'publishedReference' is null",
                    sourceRef);
                continue;
            }

            XWikiDocument sourceDoc = xwiki.getDocument(sourceRef, xcontext);
            XWikiDocument publishedDoc = xwiki.getDocument(publishedReference, xcontext).clone();
            if (publishedDoc.isNew()) {
                publishedDoc.setHidden(true);
                publishedDoc.newXObject(new LocalDocumentReference("XWiki", "XWikiPreferences"), xcontext);
            }

            LocalDocumentReference pinnedPagesClass = new LocalDocumentReference("XWiki", "PinnedChildPagesClass");
            BaseObject pinnedPageObject = sourceDoc.getXObject(pinnedPagesClass);
            if (pinnedPageObject != null) {
                logger.debug("[publishInternal] Update pinned page for space [{}]",
                    sourceRef.getLastSpaceReference());
                BaseObject publishedDocPinnedPageObject = publishedDoc.getXObject(pinnedPagesClass);

                if (publishedDocPinnedPageObject == null) {
                    publishedDocPinnedPageObject = publishedDoc.newXObject(pinnedPagesClass, xcontext);
                }
                List<String> pinnedPagesList = (List<String>) pinnedPageObject.getListValue("pinnedChildPages");
                List<String> pinnedPagesListFiltered = pinnedPagesList.stream()
                    .filter(x -> {
                        // Note in the pinned pages in the list end with '/', so to resolve the page reference we
                        // need to remove this
                        String pageName = x.replaceAll("/$", "");
                        DocumentReference targetPinnedPage =
                            new DocumentReference("WebHome",
                                new SpaceReference(pageName, publishedReference.getLastSpaceReference()));
                        try {
                            return xwiki.exists(targetPinnedPage, xcontext);
                        } catch (XWikiException e) {
                            logger.error("Can't detect if page exist at ref [{}]", targetPinnedPage, e);
                            return false;
                        }
                    })
                    .collect(Collectors.toList());

                publishedDocPinnedPageObject.setStringListValue("pinnedChildPages", pinnedPagesListFiltered);

                publishedDoc.getAuthors().setEffectiveMetadataAuthor(userReference);
                publishedDoc.getAuthors().setOriginalMetadataAuthor(userReference);
                xwiki.saveDocument(publishedDoc, publicationComment, xcontext);
            }
        }
    }

    private DocumentReference getContentPage(XWikiDocument page, Map<String, Object> configuration)
        throws QueryException, XWikiException
    {
        if (page == null || configuration == null) {
            return null;
        }

        boolean unversioned = (page.getXObject(BookVersionsConstants.BOOKPAGE_CLASS_REFERENCE)
            .getIntValue(BookVersionsConstants.BOOKPAGE_PROP_UNVERSIONED) == 1);
        return unversioned ? page.getDocumentReference() : getInheritedContentReference(page.getDocumentReference(),
            (DocumentReference) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_VERSION));
    }

    private boolean isToBePublished(DocumentReference pageReference, XWikiDocument variant, Map<String, Object> configuration,
        Locale userLocale) throws XWikiException, QueryException
    {
        if (pageReference == null || configuration == null) {
            return false;
        }
        if (userLocale == null) {
            userLocale = new Locale(BookVersionsConstants.DEFAULT_LOCALE);
        }

        List<DocumentReference> variants = getPageVariants(pageReference);
        String status = getPageStatus(pageReference);
        boolean publishOnlyComplete =
            (boolean) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_PUBLISHONLYCOMPLETE);
        boolean excludePagesOutsideVariant = false;
        if (variant != null) {
            excludePagesOutsideVariant = (variant.getXObject(BookVersionsConstants.VARIANT_CLASS_REFERENCE)
                .getIntValue(BookVersionsConstants.VARIANT_PROP_EXCLUDE) == 1);
        }
        String language = (String) configuration.get(BookVersionsConstants.PUBLICATIONCONFIGURATION_PROP_LANGUAGE);
        if (isMarkedDeleted(pageReference)) {
            // Page is marked as deleted
            logger.debug("[isToBePublished] Page [{}] is ignored because it is marked as deleted.", pageReference);
            logger.info(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.isToBePublished."
                + "markedAsDeleted", userLocale, pageReference));
            return false;
        } else if (publishOnlyComplete && status != null
            && !status.equals(BookVersionsConstants.PAGESTATUS_PROP_STATUS_COMPLETE))
        {
            // Page doesn't have a "complete" status but only those are published
            logger.debug("[isToBePublished] Page [{}] is ignored because its status is [{}] and only [{}] page are "
                + "published.", pageReference, status, BookVersionsConstants.PAGESTATUS_PROP_STATUS_COMPLETE);
            logger.info(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.isToBePublished."
                + "status", userLocale, pageReference, status,
                BookVersionsConstants.PAGESTATUS_PROP_STATUS_COMPLETE));
            return false;
        } else if (variant == null && variants != null && !variants.isEmpty()) {
            // No variant to be published AND page is associated with variant(s)
            logger.debug("[isToBePublished] Page [{}] is ignored because it is associated with variants.",
                pageReference);
            logger.info(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.isToBePublished."
                + "variantPage", userLocale, pageReference));
            return false;
        } else if (variant != null && variants != null && !variants.contains(variant.getDocumentReference())
            && (excludePagesOutsideVariant || (!excludePagesOutsideVariant && !variants.isEmpty())))
        {
            // A variant is to be published AND the page is associated with other variant(s) AND
            // (pages outside the variant are excluded
            // OR pages outside the variant are excluded but the page is associated to the published variant)
            logger.debug("[isToBePublished] Page [{}] is ignored because it is not associated with the published "
                + "variant [{}].", pageReference, variant);
            logger.info(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.isToBePublished."
                    + "otherVariant", userLocale, pageReference, variant));
            return false;
        } else if (StringUtils.isNotEmpty(language)) {
            Map<String, Map<String, Object>> languageData = getLanguageData(pageReference);
            if (languageData.get(language) == null) {
                // The page has no translation
                logger.debug("[isToBePublished] Page [{}] is ignored because it is not associated with the "
                    + "published language [{}].", pageReference, language);
                logger.info(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.isToBePublished."
                        + "noTranslation", userLocale, pageReference, language));
                return false;
            } else if (languageData.get(language).get(BookVersionsConstants.PAGETRANSLATION_HASTRANSLATED) == null
                || !((boolean) languageData.get(language).get(BookVersionsConstants.PAGETRANSLATION_HASTRANSLATED)))
            {
                // The page has no "Translated" translation
                logger.debug("[isToBePublished] Page [{}] is ignored because the translation doesn't have a [{}] "
                    + "status.", pageReference, PageTranslationStatus.TRANSLATED);
                logger.info(localization.getTranslationPlain("BookVersions.DefaultBookVersionsManager.isToBePublished."
                        + "noCompleteTranslation", userLocale, pageReference, PageTranslationStatus.TRANSLATED));
                return false;
            }
        }
        return true;
    }

    @Override
    public String getPageStatus(DocumentReference pageReference)
    {
        String result = null;
        if (pageReference == null) {
            return result;
        }

        String statusQueryString = "select prop.value from BaseObject as obj, StringProperty as prop where obj.className = :className "
            + "and obj.name = :objectName and prop.id.id = obj.id and prop.name = :propName";
        try {
            List<String> statusList = this.queryManagerProvider.get()
                .createQuery(statusQueryString, Query.HQL)
                .bindValue("className", localSerializer.serialize(BookVersionsConstants.PAGESTATUS_CLASS_REFERENCE))
                .bindValue("objectName", localSerializer.serialize(pageReference))
                .bindValue("propName", BookVersionsConstants.PAGESTATUS_PROP_STATUS)
                .setWiki(pageReference.getWikiReference().getName()).execute();
            for(String status : statusList) {
                result = status;
                break;
            }
        } catch (QueryException e) {
            logger.error("Could not compute the status for page [{}] : [{}]", pageReference, e);
        }

        return result;
    }

    private List<String> getPageReferenceTree(DocumentReference sourceReference) throws QueryException
    {
        // Can be refactored with queryPages
        if (sourceReference == null) {
            return Collections.emptyList();
        }
        SpaceReference spaceReference = sourceReference.getLastSpaceReference();
        String spaceSerialized = localSerializer.serialize(spaceReference);
        String spacePrefix = spaceSerialized.replaceAll("([%_/])", "/$1").concat(".%");

        logger.debug("[getPageReferenceTree] spaceSerialized : [{}]", spaceSerialized);
        logger.debug("[getPageReferenceTree] spacePrefix : [{}]", spacePrefix);

        // Query inspired from getDocumentReferences of DefaultModelBridge.java in xwiki-platform
        List<String> result = this.queryManagerProvider.get()
            .createQuery(", BaseObject as obj where doc.fullName = obj.name and obj.className = :class "
                + "and doc.space like :space escape '/' order by doc.fullName asc", Query.HQL)
            .bindValue("class", localSerializer.serialize(BookVersionsConstants.BOOKPAGE_CLASS_REFERENCE))
            .bindValue("space", spacePrefix).setWiki(sourceReference.getWikiReference().getName()).execute();
        // add the source as first element, as it's given by the query
        result.add(0, sourceReference.toString());

        logger.debug("[getPageReferenceTree] result : [{}]", result);

        return result;
    }

    private Map<String, Map<String, Object>> getLanguageData(Document document)
    {

        Map<String, Map<String, Object>> languageData = new HashMap<String, Map<String, Object>>();
        if (document == null) {
            return languageData;
        }

        XDOM xdom = document.getXDOM();

        List<MacroBlock> macros = xdom.getBlocks(MACRO_MATCHER, Block.Axes.DESCENDANT_OR_SELF);
        for (MacroBlock macroBlock : macros) {
            if (macroBlock.getId().equals(BookVersionsConstants.CONTENTTRANSLATION_MACRO_ID)) {
                String language = macroBlock.getParameter(BookVersionsConstants.PAGETRANSLATION_LANGUAGE);

                if (language != null && !language.isEmpty()) {

                    // Title
                    String title = macroBlock.getParameter(BookVersionsConstants.PAGETRANSLATION_TITLE);

                    // Status
                    String statusParameterValue = macroBlock.getParameter(BookVersionsConstants.PAGETRANSLATION_STATUS);
                    PageTranslationStatus status = PageTranslationStatus.NOT_TRANSLATED;
                    if (statusParameterValue != null && !statusParameterValue.isEmpty()
                        && statusParameterValue.toLowerCase().equals("translated"))
                    {
                        status = PageTranslationStatus.TRANSLATED;
                    }
                    if (statusParameterValue != null && !statusParameterValue.isEmpty()
                        && statusParameterValue.toLowerCase().equals("outdated"))
                    {
                        status = PageTranslationStatus.OUTDATED;
                    }

                    // Default language
                    String isDefault = macroBlock.getParameter(BookVersionsConstants.PAGETRANSLATION_ISDEFAULT);

                    Map<String, Object> currentLanguageData = new HashMap<String, Object>();
                    currentLanguageData.put(BookVersionsConstants.PAGETRANSLATION_TITLE,
                        title != null && !title.isEmpty() ? title : "");
                    currentLanguageData.put(BookVersionsConstants.PAGETRANSLATION_STATUS,
                        status != null ? status : PageTranslationStatus.NOT_TRANSLATED);
                    if (languageData.get(language) != null
                        && languageData.get(language).get(BookVersionsConstants.PAGETRANSLATION_HASTRANSLATED) != null
                        && (boolean) languageData.get(language).get(BookVersionsConstants.PAGETRANSLATION_HASTRANSLATED)
                    )
                    {
                        // the current language already has a Translated status which should be kept
                        currentLanguageData.put(BookVersionsConstants.PAGETRANSLATION_HASTRANSLATED, true);
                    } else {
                        currentLanguageData.put(BookVersionsConstants.PAGETRANSLATION_HASTRANSLATED,
                            status == PageTranslationStatus.TRANSLATED);
                    }
                    currentLanguageData.put(BookVersionsConstants.PAGETRANSLATION_ISDEFAULT,
                        isDefault != null && !isDefault.isEmpty() ? Boolean.valueOf(isDefault) : false);

                    languageData.put(language, currentLanguageData);
                }
            }
        }

        return languageData;
    }

    @Override
    public Map<String, Map<String, Object>> getLanguageData(DocumentReference documentReference)
    {
        Map<String, Map<String, Object>> languageData = new HashMap<String, Map<String, Object>>();

        String languageQueryString =
            "select language.value, title.value, status.value, isDefault.value from BaseObject as obj, "
                + "StringProperty as language, StringProperty as title, StringProperty as status, IntegerProperty as isDefault where "
                + "obj.className = :className and obj.name = :objectName and language.id.id = obj.id and language.name = :languageName and "
                + "title.id.id = obj.id and title.name = :titleName and status.id.id = obj.id and status.name = :statusName and "
                + "isDefault.id.id = obj.id and isDefault.name = :isDefaultName";
        try {
            List<Object[]> documentlanguageData =
                this.queryManagerProvider.get().createQuery(languageQueryString, Query.HQL)
                    .bindValue("className",
                        this.localSerializer.serialize(BookVersionsConstants.PAGETRANSLATION_CLASS_REFERENCE))
                    .bindValue("objectName", this.localSerializer.serialize(documentReference))
                    .bindValue("languageName", BookVersionsConstants.PAGETRANSLATION_LANGUAGE)
                    .bindValue("titleName", BookVersionsConstants.PAGETRANSLATION_TITLE)
                    .bindValue("statusName", BookVersionsConstants.PAGETRANSLATION_STATUS)
                    .bindValue("isDefaultName", BookVersionsConstants.PAGETRANSLATION_ISDEFAULT)
                    .setWiki(documentReference.getWikiReference().getName()).execute();
            for (Object[] entry : documentlanguageData) {
                if (entry == null || (entry != null && entry.length == 0)) {
                    continue;
                }

                String language = (String) entry[0];
                if (language != null && !language.isEmpty()) {
                    // Title
                    String title = (String) entry[1];

                    // Status
                    String statusParameterValue = (String) entry[2];
                    Integer isDefault = (Integer) entry[3];

                    // Status
                    PageTranslationStatus status = PageTranslationStatus.NOT_TRANSLATED;
                    if (statusParameterValue != null && !statusParameterValue.isEmpty()
                        && statusParameterValue.toLowerCase().equals("translated")) {
                        status = PageTranslationStatus.TRANSLATED;
                    }
                    if (statusParameterValue != null && !statusParameterValue.isEmpty()
                        && statusParameterValue.toLowerCase().equals("outdated")) {
                        status = PageTranslationStatus.OUTDATED;
                    }

                    // Default language
                    Map<String, Object> currentLanguageData = new HashMap<String, Object>();
                    currentLanguageData.put(BookVersionsConstants.PAGETRANSLATION_TITLE,
                        title != null && !title.isEmpty() ? title : "");
                    currentLanguageData.put(BookVersionsConstants.PAGETRANSLATION_STATUS,
                        status != null ? status : PageTranslationStatus.NOT_TRANSLATED);
                    if (languageData.get(language) != null
                        && languageData.get(language).get(BookVersionsConstants.PAGETRANSLATION_HASTRANSLATED) != null
                        && (boolean) languageData.get(language)
                            .get(BookVersionsConstants.PAGETRANSLATION_HASTRANSLATED)) {
                        // the current language already has a Translated status which should be kept
                        currentLanguageData.put(BookVersionsConstants.PAGETRANSLATION_HASTRANSLATED, true);
                    } else {
                        currentLanguageData.put(BookVersionsConstants.PAGETRANSLATION_HASTRANSLATED,
                            status == PageTranslationStatus.TRANSLATED);
                    }
                    currentLanguageData.put(BookVersionsConstants.PAGETRANSLATION_ISDEFAULT,
                        isDefault != null && isDefault > 0 ? true : false);

                    languageData.put(language, currentLanguageData);
                }
            }
        } catch (QueryException e) {
            logger.error("Could not compute teh list of variants for page [{}] : [{}]", documentReference, e);
        }

        return languageData;
    }

    @Override
    public Map<String, Map<String, Object>> getLanguageData(XWikiDocument document)
    {

        Map<String, Map<String, Object>> languageData = new HashMap<String, Map<String, Object>>();
        if (document == null) {
            return languageData;
        }

        XDOM xdom = document.getXDOM();

        List<MacroBlock> macros = xdom.getBlocks(MACRO_MATCHER, Block.Axes.DESCENDANT_OR_SELF);
        for (MacroBlock macroBlock : macros) {
            if (macroBlock.getId().equals(BookVersionsConstants.CONTENTTRANSLATION_MACRO_ID)) {
                String language = macroBlock.getParameter(BookVersionsConstants.PAGETRANSLATION_LANGUAGE);

                if (language != null && !language.isEmpty()) {

                    // Title
                    String title = macroBlock.getParameter(BookVersionsConstants.PAGETRANSLATION_TITLE);

                    // Status
                    String statusParameterValue = macroBlock.getParameter(BookVersionsConstants.PAGETRANSLATION_STATUS);
                    PageTranslationStatus status = PageTranslationStatus.NOT_TRANSLATED;
                    if (statusParameterValue != null && !statusParameterValue.isEmpty()
                        && statusParameterValue.toLowerCase().equals("translated"))
                    {
                        status = PageTranslationStatus.TRANSLATED;
                    }
                    if (statusParameterValue != null && !statusParameterValue.isEmpty()
                        && statusParameterValue.toLowerCase().equals("outdated"))
                    {
                        status = PageTranslationStatus.OUTDATED;
                    }

                    // Default language
                    String isDefault = macroBlock.getParameter(BookVersionsConstants.PAGETRANSLATION_ISDEFAULT);

                    Map<String, Object> currentLanguageData = new HashMap<String, Object>();
                    currentLanguageData.put(BookVersionsConstants.PAGETRANSLATION_TITLE,
                        title != null && !title.isEmpty() ? title : "");
                    currentLanguageData.put(BookVersionsConstants.PAGETRANSLATION_STATUS,
                        status != null ? status : PageTranslationStatus.NOT_TRANSLATED);
                    if (languageData.get(language) != null
                        && languageData.get(language).get(BookVersionsConstants.PAGETRANSLATION_HASTRANSLATED) != null
                        && (boolean) languageData.get(language).get(BookVersionsConstants.PAGETRANSLATION_HASTRANSLATED)
                    )
                    {
                        // the current language already has a Translated status which should be kept
                        currentLanguageData.put(BookVersionsConstants.PAGETRANSLATION_HASTRANSLATED, true);
                    } else {
                        currentLanguageData.put(BookVersionsConstants.PAGETRANSLATION_HASTRANSLATED,
                            status == PageTranslationStatus.TRANSLATED);
                    }
                    currentLanguageData.put(BookVersionsConstants.PAGETRANSLATION_ISDEFAULT,
                        isDefault != null && !isDefault.isEmpty() ? Boolean.valueOf(isDefault) : false);

                    languageData.put(language, currentLanguageData);
                }
            }
        }

        return languageData;
    }

    @Override
    public void setLanguageData(XWikiDocument document, Map<String, Map<String, Object>> languageData)
    {
        if (document == null || languageData == null) {
            return;
        }

        for (Entry<String, Map<String, Object>> languageDataEntry : languageData.entrySet()) {
            String language = languageDataEntry.getKey();
            if (languageDataEntry == null || language == null) {
                continue;
            }

            BaseObject translationObject = null;

            for (BaseObject tObj : document.getXObjects(BookVersionsConstants.PAGETRANSLATION_CLASS_REFERENCE)) {
                if (tObj == null) {
                    continue;
                }
                String languageEntry = tObj.getStringValue(BookVersionsConstants.PAGETRANSLATION_LANGUAGE);
                if (languageEntry != null && !languageEntry.isEmpty() && languageEntry.equals(language)) {
                    translationObject = tObj;
                    break;
                }
            }

            if (translationObject == null) {
                try {
                    translationObject =
                        document.newXObject(BookVersionsConstants.PAGETRANSLATION_CLASS_REFERENCE, getXWikiContext());
                    translationObject.setStringValue(BookVersionsConstants.PAGETRANSLATION_LANGUAGE, language);
                } catch (XWikiException e) {
                    logger.error("Could not set the translation data in document [{}]", document.getDocumentReference(),
                        e);
                    return;
                }
            }

            String title = (String) languageDataEntry.getValue().get(BookVersionsConstants.PAGETRANSLATION_TITLE);
            translationObject.setStringValue(BookVersionsConstants.PAGETRANSLATION_TITLE, title != null ? title : "");

            PageTranslationStatus status =
                (PageTranslationStatus) languageDataEntry.getValue().get(BookVersionsConstants.PAGETRANSLATION_STATUS);
            translationObject.setStringValue(BookVersionsConstants.PAGETRANSLATION_STATUS,
                status != null ? status.getTranslationStatus() : null);

            boolean isDefault =
                (boolean) languageDataEntry.getValue().get(BookVersionsConstants.PAGETRANSLATION_ISDEFAULT);
            translationObject.setIntValue(BookVersionsConstants.PAGETRANSLATION_ISDEFAULT, isDefault ? 1 : 0);
        }

        // Now, remove translations that were deleted by the user or don't have a language defined
        for (BaseObject tObj : document.getXObjects(BookVersionsConstants.PAGETRANSLATION_CLASS_REFERENCE)) {
            if (tObj == null) {
                continue;
            }
            String languageEntry = tObj.getStringValue(BookVersionsConstants.PAGETRANSLATION_LANGUAGE);
            if (languageEntry == null || languageEntry.isEmpty() || !languageData.containsKey(languageEntry)) {
                document.removeXObject(tObj);
            }
        }
    }

    @Override
    public void resetTranslations(XWikiDocument document)
    {
        if (document == null) {
            return;
        }

        for (BaseObject tObj : document.getXObjects(BookVersionsConstants.PAGETRANSLATION_CLASS_REFERENCE)) {
            if (tObj == null) {
                continue;
            }
            document.removeXObject(tObj);
        }
    }

    @Override
    public List<String> getConfiguredLanguages(DocumentReference bookReference) throws XWikiException
    {
        if (bookReference == null) {
            return new ArrayList<String>();
        }

        XWikiContext xcontext = this.getXWikiContext();

        XWikiDocument bookDocument = xcontext.getWiki().getDocument(bookReference, xcontext);
        if (isBook(bookDocument)) {
            // Go to the location where the Languages definition is stored
            SpaceReference languagesParentSpaceReference =
                new SpaceReference(new EntityReference(BookVersionsConstants.LANGUAGES_LOCATION, EntityType.SPACE,
                    bookReference.getParent()));
            DocumentReference languagesDocumentReference =
                new DocumentReference(new EntityReference(this.getXWikiContext().getWiki().DEFAULT_SPACE_HOMEPAGE,
                    EntityType.DOCUMENT, languagesParentSpaceReference));

            XWikiDocument languagesDocument = xcontext.getWiki().getDocument(languagesDocumentReference, xcontext);
            BaseObject multilingualObj =
                languagesDocument.getXObject(BookVersionsConstants.BOOKLMULTILANGUAL_CLASS_REFERENCE);
            if (multilingualObj != null) {
                return (List<String>) multilingualObj
                    .getListValue(BookVersionsConstants.BOOKLMULTILANGUAL_PROP_LANGUAGES);
            }
        }

        return new ArrayList<String>();
    }

    @Override
    public void addLibraryReferenceClassObject(DocumentReference versionReference) throws XWikiException
    {
        if (versionReference == null) {
            return;
        }

        XWikiContext xcontext = this.getXWikiContext();
        LocalDocumentReference libraryReferenceClassRef =
            new LocalDocumentReference(Arrays.asList("BookVersions", "Code"), "LibraryReferenceClass");
        XWikiDocument versionDocument = xcontext.getWiki().getDocument(versionReference, xcontext).clone();
        versionDocument.createXObject(libraryReferenceClassRef, xcontext);
        UserReference userReference = userReferenceResolver.resolve(xcontext.getUserReference());
        versionDocument.getAuthors().setEffectiveMetadataAuthor(userReference);
        versionDocument.getAuthors().setOriginalMetadataAuthor(userReference);
        xcontext.getWiki().saveDocument(versionDocument, xcontext);
    }

    @Override
    public void removeLibraryReferenceClassObject(DocumentReference versionReference, int objectNumber)
        throws XWikiException
    {
        if (versionReference == null) {
            return;
        }

        XWikiContext xcontext = this.getXWikiContext();
        LocalDocumentReference libraryReferenceClassRef =
            new LocalDocumentReference(Arrays.asList("BookVersions", "Code"), "LibraryReferenceClass");
        XWikiDocument versionDocument = xcontext.getWiki().getDocument(versionReference, xcontext).clone();

        if (!versionDocument.isNew()) {
            List<BaseObject> objects = versionDocument.getXObjects(libraryReferenceClassRef);
            versionDocument.removeXObject(objects.get(objectNumber));
            UserReference userReference = userReferenceResolver.resolve(xcontext.getUserReference());
            versionDocument.getAuthors().setEffectiveMetadataAuthor(userReference);
            versionDocument.getAuthors().setOriginalMetadataAuthor(userReference);
            xcontext.getWiki().saveDocument(versionDocument, xcontext);
        }
    }

    @Override
    public String setPagesStatus(List<String> pageReferences, String namespaces,
        LiveDataConfiguration liveDataConfiguration, String newStatus) throws JobException
    {
        LiveDataBatchRequest jobRequest =
            new LiveDataBatchRequest(namespaces, liveDataConfiguration, pageReferences, newStatus);
        String jobId = BookVersionsConstants.SET_PAGE_STATUS_JOBID_PREFIX
            + BookVersionsConstants.SET_PAGE_STATUS_JOBID_SEPARATOR + Instant.now().toString();
        if (jobId != null) {
            jobRequest.setId(jobId);
            // The context won't be full in setPagesStatusInternal as it is executed by a job, so the user executing the
            // status change has to be passed as a parameter.
            jobRequest.setProperty("userReference", this.getXWikiContext().getUserReference());
            jobExecutor.execute(BookVersionsConstants.SET_PAGE_STATUS_JOBID, jobRequest);
        }
        return jobId;
    }

    private String getXWikisDefaultLanguage()
    {
        XWikiContext context = getXWikiContext();
        return context.getWiki().getXWikiPreference(BookVersionsConstants.XWIKIPREF_DEFAULT_LANGUAGE, context);
    }

    /**
     * Get the XWiki context.
     *
     * @return the xwiki context.
     */
    protected XWikiContext getXWikiContext()
    {
        return contextProvider.get();
    }
}
