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

import java.util.Arrays;
import java.util.List;

import org.xwiki.model.EntityType;
import org.xwiki.model.reference.EntityReference;

/**
 * Book versions constants.
 * 
 * @version $Id$
 * @since 0.1
 */
public interface BookVersionsConstants
{
    /**
     * The reference of the code location.
     */
    EntityReference BOOKVERSIONS_CODE_REFERENCE = new EntityReference("Code", EntityType.SPACE,
        new EntityReference("BookVersions", org.xwiki.model.EntityType.SPACE));

    /**
     * The reference of the Book class.
     */
    EntityReference BOOK_CLASS_REFERENCE =
        new EntityReference("BookClass", EntityType.DOCUMENT, BOOKVERSIONS_CODE_REFERENCE);

    /**
     * The reference of the library class.
     */
    EntityReference LIBRARY_CLASS_REFERENCE =
        new EntityReference("LibraryClass", EntityType.DOCUMENT, BOOKVERSIONS_CODE_REFERENCE);

    /**
     * The reference of the version class.
     */
    EntityReference VERSION_CLASS_REFERENCE =
        new EntityReference("VersionClass", EntityType.DOCUMENT, BOOKVERSIONS_CODE_REFERENCE);

    /**
     * The reference of the variant class.
     */
    EntityReference VARIANT_CLASS_REFERENCE =
        new EntityReference("VariantClass", EntityType.DOCUMENT, BOOKVERSIONS_CODE_REFERENCE);

    /**
     * The reference of the variant list class.
     */
    EntityReference VARIANTLIST_CLASS_REFERENCE =
        new EntityReference("VariantsListClass", EntityType.DOCUMENT, BOOKVERSIONS_CODE_REFERENCE);

    /**
     * The reference of the book page class.
     */
    EntityReference BOOKPAGE_CLASS_REFERENCE =
        new EntityReference("BookPageClass", EntityType.DOCUMENT, BOOKVERSIONS_CODE_REFERENCE);

    /**
     * The reference of the book versioned content class.
     */
    EntityReference BOOKVERSIONEDCONTENT_CLASS_REFERENCE =
        new EntityReference("BookVersionedContentClass", EntityType.DOCUMENT, BOOKVERSIONS_CODE_REFERENCE);

    /**
     * The reference of the library reference class.
     */
    EntityReference BOOKLIBRARYREFERENCE_CLASS_REFERENCE =
        new EntityReference("LibraryReferenceClass", EntityType.DOCUMENT, BOOKVERSIONS_CODE_REFERENCE);

    /**
     * The reference of the library reference class.
     */
    EntityReference MARKEDDELETED_CLASS_REFERENCE =
        new EntityReference("DeletedContentClass", EntityType.DOCUMENT, BOOKVERSIONS_CODE_REFERENCE);

    /**
     * The reference of the publication configuration class.
     */
    EntityReference PUBLICATIONCONFIGURATION_CLASS_REFERENCE =
        new EntityReference("PublicationConfigurationClass", EntityType.DOCUMENT, BOOKVERSIONS_CODE_REFERENCE);

    /**
     * The reference of the page translation class.
     */
    EntityReference PAGETRANSLATION_CLASS_REFERENCE =
        new EntityReference("PageTranslationClass", EntityType.DOCUMENT, BOOKVERSIONS_CODE_REFERENCE);

    /**
     * The reference of a publication data class.
     */
    EntityReference PUBLICATION_CLASS_REFERENCE =
        new EntityReference("PublicationClass", EntityType.DOCUMENT, BOOKVERSIONS_CODE_REFERENCE);

    /**
     * The data of a published collection class.
     */
    EntityReference PUBLISHEDCOLLECTION_CLASS_REFERENCE =
        new EntityReference("PublishedBookClass", EntityType.DOCUMENT, BOOKVERSIONS_CODE_REFERENCE);

    /**
     * The masterName property of a published collection data.
     */
    String PUBLISHEDCOLLECTION_PROP_MASTERNAME = "masterName";

    /**
     * The bookVersionName property of a published collection data.
     */
    String PUBLISHEDCOLLECTION_PROP_VERSIONNAME = "bookVersionName";

    /**
     * The variantName property of a published collection data.
     */
    String PUBLISHEDCOLLECTION_PROP_VARIANTNAME = "variantName";

    /**
     * The id property of a publication data.
     */
    String PUBLICATION_PROP_ID = "id";

    /**
     * The source property of a publication data.
     */
    String PUBLICATION_PROP_SOURCE = "source";

    /**
     * The publishedSpace property of a publication data.
     */
    String PUBLICATION_PROP_PUBLISHEDSPACE = "publishedSpace";

    /**
     * The source property of a publication configuration.
     */
    String PUBLICATIONCONFIGURATION_PROP_SOURCE = "source";

    /**
     * The destinationSpace property of a publication configuration.
     */
    String PUBLICATIONCONFIGURATION_PROP_DESTINATIONSPACE = "destinationSpace";

    /**
     * The version property of a publication configuration.
     */
    String PUBLICATIONCONFIGURATION_PROP_VERSION = "version";

    /**
     * The variant property of a publication configuration.
     */
    String PUBLICATIONCONFIGURATION_PROP_VARIANT = "variant";

    /**
     * The language property of a publication configuration.
     */
    String PUBLICATIONCONFIGURATION_PROP_LANGUAGE = "language";

    /**
     * The publishOnlyComplete property of a publication configuration.
     */
    String PUBLICATIONCONFIGURATION_PROP_PUBLISHONLYCOMPLETE = "publishOnlyComplete";

    /**
     * The publishPageOrder property of a publication configuration.
     */
    String PUBLICATIONCONFIGURATION_PROP_PUBLISHPAGEORDER = "publishPageOrder";

    /**
     * The publishBehaviour property of a publication configuration.
     */
    String PUBLICATIONCONFIGURATION_PROP_PUBLISHBEHAVIOUR = "publishBehaviour";

    /**
     * The cancel value of publishBehaviour property.
     */
    String PUBLICATIONCONFIGURATION_PROP_PUBLISHBEHAVIOUR_CANCEL = "cancel";

    /**
     * The update value of publishBehaviour property.
     */
    String PUBLICATIONCONFIGURATION_PROP_PUBLISHBEHAVIOUR_UPDATE = "update";

    /**
     * The republish value of publishBehaviour property.
     */
    String PUBLICATIONCONFIGURATION_PROP_PUBLISHBEHAVIOUR_REPUBLISH = "republish";

    /**
     * The unversioned property of a book page.
     */
    String BOOKPAGE_PROP_UNVERSIONED = "unversioned";

    /**
     * The status property of a book page.
     */
    String BOOKVERSIONEDCONTENT_PROP_STATUS = "status";

    /**
     * The status property of a book page.
     */
    String BOOKVERSIONEDCONTENT_PROP_STATUS_DRAFT = "draft";

    /**
     * The status property of a book page.
     */
    String BOOKVERSIONEDCONTENT_PROP_STATUS_REVIEW = "review";

    /**
     * The status property of a book page.
     */
    String BOOKVERSIONEDCONTENT_PROP_STATUS_COMPLETE = "complete";

    /**
     * The variantsList property of a book page.
     */
    String VARIANTLIST_PROP_VARIANTSLIST = "variantsList";

    /**
     * The slug validator hint.
     */
    String SLUGVALIDATION_HINT = "SlugEntityNameValidation";

    /**
     * The selected version that is stored in the session.
     */
    String SESSION_SELECTEDVERSION = "BookVersions.selectedVersion";

    /**
     * The selected variant that is stored in the session.
     */
    String SESSION_SELECTEDVARIANT = "BookVersions.selectedVariant";

    /**
     * The selected language that is stored in the session.
     */
    String SESSION_SELECTEDLANGUAGE = "BookVersions.selectedLanguage";

    /**
     * The selected version that is stored in the session.
     */
    String VERSION_PROP_PRECEDINGVERSION = "precedingVersionReference";

    /**
     * The library reference of a book library reference.
     */
    String BOOKLIBRARYREFERENCE_PROP_LIBRARY = "libraryReference";

    /**
     * The library version reference of a book library reference.
     */
    String BOOKLIBRARYREFERENCE_PROP_LIBRARYVERSION = "libraryVersionReference";

    /**
     * The location of version definitions, in a book / library.
     */
    String VERSIONS_LOCATION = "Versions";

    /**
     * The location of variant definitions, in a book / library.
     */
    String VARIANTS_LOCATION = "Variants";

    /**
     * The location of variant definitions, in a book / library.
     */
    String PUBLICATIONJOB_TYPE = "BookPublicationJob";

    /**
     * The language property in the Page Translation class.
     */
    String PAGETRANSLATION_LANGUAGE = "language";

    /**
     * The title property in the Page Translation class.
     */
    String PAGETRANSLATION_TITLE = "title";

    /**
     * The status property in the Page Translation class.
     */
    String PAGETRANSLATION_STATUS = "status";

    /**
     * The 'not translated' status property in the Page Translation class.
     */
    String PAGETRANSLATION_STATUS_UNTRANSLATED = "notTranslated";

    /**
     * The 'in progress' status property in the Page Translation class.
     */
    String PAGETRANSLATION_STATUS_INPROGRESS = "inProgress";

    /**
     * The 'translated' status property in the Page Translation class.
     */
    String PAGETRANSLATION_STATUS_TRANSLATED = "translated";

    /**
     * The default flag in the Page Translation class.
     */
    String PAGETRANSLATION_ISDEFAULT = "isDefault";

    /**
     * The content translation macro id.
     */
    String CONTENTTRANSLATION_MACRO_ID = "contentTranslation";

    /**
     * The default title value for translated documents.
     */
    String DEFAULT_TRANSLATION_TITLE = "$!services.bookversions.getTranslatedTitle($doc.getDocumentReference())";

    /**
     * The message key about the missing title value for the selected translation.
     */
    String MISSING_TRANSLATION_TITLE_KEY = "BookVersions.languages.title.missing";

    /**
     * The list of objects to remove in published pages.
     */
    List<EntityReference> PUBLICATION_REMOVEDOBJECTS = Arrays.asList(
        BookVersionsConstants.BOOK_CLASS_REFERENCE,
        BookVersionsConstants.LIBRARY_CLASS_REFERENCE,
        BookVersionsConstants.BOOKVERSIONEDCONTENT_CLASS_REFERENCE,
        BookVersionsConstants.BOOKPAGE_CLASS_REFERENCE,
        BookVersionsConstants.MARKEDDELETED_CLASS_REFERENCE,
        BookVersionsConstants.VARIANTLIST_CLASS_REFERENCE,
        BookVersionsConstants.PUBLICATION_CLASS_REFERENCE
    );

    /**
     * The first part of the publication job's ID
     */
    String PUBLICATION_JOBID_PREFIX = "BookVersionsPublication";

    /**
     * The separator of the publication job's ID
     */
    String PUBLICATION_JOBID_SEPARATOR = "_";

    /**
     * The ID of the variant macro.
     */
    String VARIANT_MACRO_ID = "variant";

    /**
     * The name property of a variant macro.
     */
    String VARIANT_MACRO_PROP_NAME = "name";

    /**
     * The ID of the includeLibrary macro.
     */
    String INCLUDELIBRARY_MACRO_ID = "includeLibrary";

    /**
     * The keyReference property of an includeLibrary macro.
     */
    String INCLUDELIBRARY_MACRO_PROP_KEYREFERENCE = "keyReference";

    /**
     * The ID of the include macro.
     */
    String INCLUDE_MACRO_ID = "include";

    /**
     * The reference property of an include macro.
     */
    String INCLUDE_MACRO_PROP_REFERENCE = "reference";
}
