//Copyright 2007 Google Inc.
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at

//http://www.apache.org/licenses/LICENSE-2.0

//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.

package com.google.enterprise.connector.sharepoint.state;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.joda.time.DateTime;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;

import com.google.enterprise.connector.sharepoint.client.Attribute;
import com.google.enterprise.connector.sharepoint.client.SPConstants;
import com.google.enterprise.connector.sharepoint.client.Util;
import com.google.enterprise.connector.sharepoint.spiimpl.SPDocument;
import com.google.enterprise.connector.sharepoint.spiimpl.SharepointException;
import com.google.enterprise.connector.spi.SpiConstants.ActionType;

/**
 * Represents a SharePoint List/Library as a stateful object
 *
 * @author nitendra_thakur
 */
public class ListState implements StatefulObject {
    protected String key = null;

    /**
     * Whether the underlying SharePoint object that this object was created to
     * represent actually exists. This variable is periodically set to false by
     * GlobalState, and then set true if the underlying object is found to be
     * still there.
     */
    private boolean exists = true; // By default mark all list state as exisitng
    // when they are created.

    private String listTitle = "";
    private String listURL = "";

    /**
     * Flag to indicate if a this list is to be fed to GSA as a document or is
     * it to be ignored
     */
    private boolean sendListAsDocument;

    /**
     * To store the the recent change token required to make the web service
     * call next time.
     */
    private String changeToken;
    /**
     * To store the the previous change token in memory. we might need to roll
     * back.
     */
    private String CachedPrevChangeToken;
    /**
     * To keep track of the latest change token value that we might lose during
     * rolling back.
     */
    private String LatestCachedChangeToken;

    /**
     * To store all the extraIDs for which delete feed has been sent. This is
     * also kept in memory.
     */
    private Set<String> cachedDeletedIDs = new HashSet<String>();

    private static final Logger LOGGER = Logger.getLogger(ListState.class.getName());

    /**
     * this should be set by the main Sharepoint client every time it
     * successfully crawls a SPDocument. For Lists that are NOT current, this is
     * maintained in the persistent state.
     */
    SPDocument lastDocument;

    /**
     * This doc is the last doc sent to CM and successfully fed to GSA with
     * action=ADD. This is required during incremental crawl to tell the WS from
     * where to look for more docs
     */
    SPDocument lastDocProcessedForWS;

    /**
     * an ordered list of SPDocuments due to be fed to the Connector Manager
     */
    private List<SPDocument> crawlQueue = null;

    private final Collator collator = Util.getCollator();

    private String type;
    private Calendar lastMod;
    private String baseTemplate;
    private String parentWebTitle;
    private String parentWeb;
    private ArrayList<Attribute> attrs = new ArrayList<Attribute>();
    private String listConst = "/Lists";

    /**
     * To keep track of those IDs which can not be discovered by making any web
     * service call. For Example, it is not possible to track the documents if
     * their parent folder is deleted.
     */
    private StringBuffer extraIDs = new StringBuffer();
    private StringBuffer attchmnts = new StringBuffer();
    /**
     * To keep track of the document with the biggest ID that has been
     * discovered from this list
     */
    private int biggestID = 0;
    /**
     * Helps to make web service call which returns the results page by page
     */
    private String nextPage = null;
    /**
     * Indicates list level change. Decides whether the list should be sent as a
     * separate doc.
     */
    private boolean isNewList;

    private String spType;
    private String feedType;

    /**
     * The timestamp of when was the list crawled last time by the connector
     */
    private String lastCrawledDateTime;

    /**
     * No-args constructor.
     */
    public ListState(final String inSPType, final String inFeedType) {
        spType = inSPType;
        feedType = inFeedType;
    }

    /**
     * @param inInternalName
     * @param inTitle
     * @param inType
     * @param inLastMod
     * @param inBaseTemplate
     * @param inUrl
     * @param inParentWeb
     * @param inParentWebTitle
     * @param inSPType
     * @param inFeedType
     * @throws SharepointException
     */
    public ListState(final String inInternalName, final String inTitle,
            final String inType, final Calendar inLastMod,
            final String inBaseTemplate, final String inUrl,
            final String inParentWeb, final String inParentWebTitle,
            final String inSPType, final String inFeedType)
            throws SharepointException {

        LOGGER.config("inInternalName[" + inInternalName + "], inType["
                + inType + "], inLastMod[" + inLastMod + "], inBaseTemplate["
                + inBaseTemplate + "], inUrl[" + inUrl + "], inParentWebTitle["
                + inParentWebTitle + "]");

        if (inInternalName == null) {
            throw new SharepointException("Unable to find Internal name");
        }
        key = inInternalName;
        listTitle = inTitle;
        parentWebTitle = inParentWebTitle;
        parentWeb = inParentWeb;
        type = inType;
        lastMod = inLastMod;
        baseTemplate = inBaseTemplate;
        listURL = inUrl;
        spType = inSPType;
        feedType = inFeedType;
    }

    /**
     * @return list type (generic / document libraries)
     */
    public String getType() {
        return type;
    }

    /**
     * @return the base templete
     */
    public String getBaseTemplate() {
        return baseTemplate;
    }

    /**
     * @param inBaseTemplate
     */
    public void setBaseTemplate(final String inBaseTemplate) {
        if (inBaseTemplate != null) {
            baseTemplate = inBaseTemplate;
        }
    }

    /**
     * @return the list properties
     */
    public ArrayList<Attribute> getAttrs() {
        return attrs;
    }

    /**
     * @param attrs
     */
    public void setAttrs(final ArrayList<Attribute> attrs) {
        this.attrs = attrs;
    }

    /**
     * @param key
     * @param value
     */
    public void setAttribute(final String key, final String value) {
        if (attrs == null) {
            attrs = new ArrayList<Attribute>();
        }
        if (key != null) {
            attrs.add(new Attribute(key, value));
        }
    }

    /**
     * @param inUrl
     */
    public void setUrl(final String inUrl) {
        if (inUrl != null) {
            listURL = inUrl;
        }
    }

    /**
     * @return the list constant which is typically used for constructing the
     *         document URLs from the list URL
     */
    public String getListConst() {
        return listConst;
    }

    /**
     * @param inListConst
     */
    public void setListConst(final String inListConst) {
        if (inListConst != null) {
            listConst = inListConst;
        }
    }

    /**
     * @return the parent web title
     */
    public String getParentWebTitle() {
        return parentWebTitle;
    }

    /**
     * @param inParentWebTitle
     */
    public void setParentWebTitle(final String inParentWebTitle) {
        if (null != inParentWebTitle) {
            parentWebTitle = inParentWebTitle;
        }
    }

    /**
     * @return the boolean value depicting if the list can contain folders
     */
    public boolean canContainFolders() {
        if (collator.equals(type, SPConstants.DOC_LIB)
                || collator.equals(type, SPConstants.GENERIC_LIST)
                || collator.equals(type, SPConstants.ISSUE)) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * @return the boolean value depicting if the list a Document library
     */
    public boolean isDocumentLibrary() {
        if (collator.equals(type, SPConstants.DOC_LIB)) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * @return the boolean value depicting if the items of this list contain
     *         attachments
     */
    public boolean canContainAttachments() {
        if (collator.equals(type, SPConstants.GENERIC_LIST)
                || collator.equals(type, SPConstants.ISSUE)
                || collator.equals(type, SPConstants.DISCUSSION_BOARD)) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * @return the boolean value depicting if the list can contain link sites
     */
    public boolean isLinkSite() {
        if ((collator.equals(baseTemplate, SPConstants.ORIGINAL_BT_LINKS))
                || (collator.equals(baseTemplate, SPConstants.BT_SITESLIST))) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Get the lastMod time.
     *
     * @return time the List was last modified
     */
    public DateTime getLastMod() {
        return Util.calendarToJoda(lastMod);
    }

    /**
     * Set the lastMod time.
     *
     * @param inLastMod time
     */
    public void setLastMod(final DateTime inLastMod) {
        lastMod = Util.jodaToCalendar(inLastMod);
    }

    /**
     * @return the l;ast modified date
     */
    public Calendar getLastModCal() {
        return lastMod;
    }

    /**
     * Return lastMod in String form.
     *
     * @return lastMod string-ified
     */
    public String getLastModString() {
        return Util.formatDate(lastMod);
    }

    /**
     * Get the primary key.
     *
     * @return primary key
     */
    public String getPrimaryKey() {
        return key;
    }

    /**
     * Sets the primary key.
     *
     * @param newKey
     */
    public void setPrimaryKey(final String newKey) {
        // primary key cannot be null
        if (newKey != null) {
            key = newKey;
        }
    }

    /**
     * Checks if the list is still existing
     */
    public boolean isExisting() {
        return exists;
    }

    /**
     * mark the list as existing/non-existing
     */
    public void setExisting(boolean existing) {
        exists = existing;
    }

    /**
     * Compares this ListState to another (for the Comparable interface).
     * Comparison is first on the lastMod date. If that produces a tie, the
     * primary key (the GUID) is used as tie-breaker.
     *
     * @param o other ListState. If null, returns 1.
     * @return the usual integer result: -1 if this object is less, 1 if it's
     *         greater, 0 if equal (which should only happen for the identity
     *         comparison).
     */
    public int compareTo(final StatefulObject o) {
        ListState other = null;
        if (o instanceof ListState) {
            other = (ListState) o;
        }
        if ((other == null) || (other.lastMod == null)) {
            return 1; // anything is greater than null
        }
        if (lastMod == null) {
            return -1;
        }
        final int lastModComparison = lastMod.getTime().compareTo(other.lastMod.getTime());
        if (lastModComparison != 0) {
            return lastModComparison;
        } else {
            return key.compareTo(other.key);
        }
    }

    /**
     * For List equality comparison
     */
    public boolean equals(final Object obj) {
        if (obj == null) {
            return false;
        }
        ListState other = null;
        if (obj instanceof ListState) {
            other = (ListState) obj;
            if (key.equals(other.getPrimaryKey())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Return the date which should be passed to Web Services (the
     * GetListItemChanges call of ListWS) in order to find new items for this
     * List. This should be called in preference to getLastDocCrawled() or any
     * other lower-level method, since this method can apply some intelligence,
     * e.g. looking at the crawl queue, and that intelligence may change over
     * time. Current algorithm: take the later of the date in
     * getLastDocCrawled() and the crawl queue, IF ANY. Otherwise, take the
     * lastMod of the List itself.
     *
     * @return Calendar suitable for passing to WebServices
     */
    public Calendar getDateForWSRefresh() {
        Calendar date = Util.jodaToCalendar(getLastMod()); // our default

        // Check if there was some doc that was last sent and use its last
        // modified date in case if it is later than that of the list
        if (getLastDocForWSRefresh() != null) {
            final Calendar lastCrawlQueueDate = getLastDocForWSRefresh().getLastMod();
            if (lastCrawlQueueDate.before(date)) {
                date = lastCrawlQueueDate;
            }
        }
        return date;
    }

    /**
     * Return the most suitable DocID to start the crawl. Used in case of
     * SP2007. Ideally we should start from the lastDoc. But if there is a crawl
     * queue whose last entry is greater then the lastDoc, we'll start from the
     * last doc of the crawl queue.
     *
     * @return {@link SPDocument}
     */
    public SPDocument getLastDocForWSRefresh() {
        // We know for sure that checkPoint() marked this doc as the last
        // successfully sent doc. So for next incremental call use this doc as
        // reference
        return getLastDocProcessedForWS();
    }

    /**
     * @return the crawl queue containg the documents from this list
     */
    public List<SPDocument> getCrawlQueue() {
        return crawlQueue;
    }

    /**
     * Debug routine: dump the crawl queue to stdout. (this is deliberately in
     * preference to log messages, since it's much easier to follow in Eclipse.)
     */
    public void dumpCrawlQueue() {
        if ((crawlQueue != null) && (crawlQueue.size() > 0)) {
            LOGGER.config("Crawl queue for " + getListURL());
            for (int iDoc = 0; iDoc < crawlQueue.size(); ++iDoc) {
                final SPDocument doc = (SPDocument) crawlQueue.get(iDoc);
                LOGGER.config(doc.getLastMod().getTime() + ", " + doc.getUrl());
                doc.dumpAllAttrs();
            }
        } else {
            LOGGER.config("Empty crawl queue for " + getListURL());
        }
    }

    /**
     * @param inCrawlQueue
     */
    public void setCrawlQueue(final List<SPDocument> inCrawlQueue) {
        crawlQueue = inCrawlQueue;
    }

    /**
     * @param doc : to be removed from the crawl queue
     */
    public void removeDocFromCrawlQueue(final SPDocument doc) {
        if (null == doc) {
            LOGGER.log(Level.WARNING, "Requested document for removal is null. ");
            return;
        }
        if (null == crawlQueue) {
            LOGGER.log(Level.WARNING, "Request for document removal is received when Crawl Queue is empty. docID [ "
                    + doc.getDocId() + " ], docURL [ " + doc.getUrl() + " ]. ");
            return;
        }
        boolean status = crawlQueue.remove(doc);
        LOGGER.log(Level.FINE, "Document Removed from Crawl Queue. docID [ "
                + doc.getDocId() + " ], docURL [ " + doc.getUrl()
                + " ], Action [ " + doc.getAction() + " ], deleteStatus [ "
                + status + " ], currentCrawlQueueSize [ " + crawlQueue.size()
                + " ]. ");
    }

    /**
     * Create a DOM tree for the entire ListState object.
     *
     * @param domDoc DOM node for the containing XML document, which is
     *            necessary for creating new DOM nodes
     * @return Node head of DOM tree
     * @throws SharepointException
     */
    public Node dumpToDOM(final Document domDoc) throws SharepointException {
        final Element element = domDoc.createElement(SPConstants.LIST_STATE);
        element.setAttribute(SPConstants.STATE_ID, getPrimaryKey());

        if (listURL != null) {
            element.setAttribute(SPConstants.STATE_URL, listURL);
        }

        final String strLastMod = getLastModString();
        if (strLastMod != null) {
            element.setAttribute(SPConstants.STATE_LASTMODIFIED, strLastMod);
        }

        if (getLastCrawledDateTime() != null) {
            element.setAttribute(SPConstants.LAST_CRAWLED_DATETIME, getLastCrawledDateTime());
        }

        if (null == type) {
            type = "";
        }
        element.setAttribute(SPConstants.STATE_TYPE, type);
        if (SPConstants.ALERTS_TYPE.equalsIgnoreCase(type)) {
            if (getIDs() != null && getIDs().length() != 0) {
                final Element IDsTmp = domDoc.createElement(SPConstants.STATE_EXTRAIDS_ALERTS);
                final Text IDsText = domDoc.createTextNode(getIDs().toString());
                IDsTmp.appendChild(IDsText);
                element.appendChild(IDsTmp);
            }
        } else {
            if (SPConstants.SP2007.equalsIgnoreCase(spType)) {
                if ((changeToken == null) || (changeToken.length() == 0)) {
                    if ((CachedPrevChangeToken == null)
                            || (CachedPrevChangeToken.length() == 0)) {
                        element.setAttribute(SPConstants.STATE_CHANGETOKEN, "");
                    } else {
                        element.setAttribute(SPConstants.STATE_CACHED_CHANGETOKEN, CachedPrevChangeToken);
                    }
                } else {
                    element.setAttribute(SPConstants.STATE_CHANGETOKEN, changeToken);
                }
                if (SPConstants.CONTENT_FEED.equalsIgnoreCase(feedType)) {
                    if (canContainFolders() && getIDs() != null
                            && getIDs().length() != 0) {
                        Element IDsTmp = domDoc.createElement(SPConstants.STATE_EXTRAIDS_FOLDERS);
                        final Text IDsText = domDoc.createTextNode(getIDs().toString());
                        IDsTmp.appendChild(IDsText);
                        element.appendChild(IDsTmp);
                    }

                    if (canContainAttachments() && getAttchmnts() != null
                            && getAttchmnts().length() != 0) {
                        Element attchmntsTmp = domDoc.createElement(SPConstants.STATE_EXTRAIDS_ATTACHMENTS);
                        final Text IDsText = domDoc.createTextNode(getAttchmnts().toString());
                        attchmntsTmp.appendChild(IDsText);
                        element.appendChild(attchmntsTmp);
                    }

                    element.setAttribute(SPConstants.STATE_BIGGESTID, String.valueOf(biggestID));

                    // Dump all the delete cache ids to the state file so that
                    // we remember it even after a connector restart. Sending
                    // duplicate delete feeds hangs the super GSA. This is very
                    // serious so toavoid the same these ids are required which
                    // will be checked before sending a delete feed
                    if (cachedDeletedIDs != null && cachedDeletedIDs.size() > 0) {
                        StringBuffer deletedListItemIDs = new StringBuffer();
                        boolean firstElement = true;
                        for (String deletedID : cachedDeletedIDs) {
                            if (firstElement) {
                                deletedListItemIDs.append(deletedID);
                                firstElement = false;
                            } else {
                                deletedListItemIDs.append(SPConstants.HASH).append(deletedID);
                            }
                        }
                        element.setAttribute(SPConstants.STATE_DELETED_LIST_ITEMIDS, deletedListItemIDs.toString());
                    }
                }
            }

            // dump the "last doc crawled"
            // Do not refer to lastDocument. It is used to work with the crawl
            // queue to identify if there are any pending docs from previous
            // batch traversal. The one that will be helpful for connector in
            // future web service calls is lastDocProcessedForWS
            if (lastDocProcessedForWS != null) {
                final Element elementLastDocCrawled = domDoc.createElement(SPConstants.STATE_LASTDOCCRAWLED);
                element.appendChild(elementLastDocCrawled);

                if (lastDocProcessedForWS.getDocId() != null) {
                    elementLastDocCrawled.setAttribute(SPConstants.STATE_ID, lastDocProcessedForWS.getDocId());
                }

                if (SPConstants.SP2007.equalsIgnoreCase(spType)) {
                    if (lastDocProcessedForWS.getFolderLevel() != null
                            && lastDocProcessedForWS.getFolderLevel().length() != 0) {
                        elementLastDocCrawled.setAttribute(SPConstants.STATE_FOLDER_LEVEL, lastDocProcessedForWS.getFolderLevel());
                    }
                    if (SPConstants.CONTENT_FEED.equalsIgnoreCase(feedType)) {
                        if (lastDocProcessedForWS.getAction() != null) {
                            elementLastDocCrawled.setAttribute(SPConstants.STATE_ACTION, lastDocProcessedForWS.getAction().toString());
                        }
                    }
                }
                if (lastDocProcessedForWS.getLastDocLastModString() != null) {
                    elementLastDocCrawled.setAttribute(SPConstants.STATE_LASTMODIFIED, lastDocProcessedForWS.getLastDocLastModString());
                }
            }
        }

        return element;
    }

    /**
     * Reload this ListState from a DOM tree. The opposite of dumpToDOM().
     *
     * @param element the DOM element
     * @throws SharepointException if the DOM tree is not a valid representation
     *             of a ListState
     */
    public void loadFromDOM(final Element element) throws SharepointException {
        if (element == null) {
            throw new SharepointException("element not found");
        }
        key = element.getAttribute(SPConstants.STATE_ID);
        if ((key == null) || (key.length() == 0)) {
            throw new SharepointException("Invalid XML: no id attribute");
        }
        listURL = element.getAttribute(SPConstants.STATE_URL);

        try {
            final String lastModString = element.getAttribute(SPConstants.STATE_LASTMODIFIED);
            lastMod = Util.jodaToCalendar(Util.parseDate(lastModString));
        } catch (final Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to load Last Modified for list [ "
                    + listURL + " ]. ", e);
        }

        String lstCrawleddateTime = element.getAttribute(SPConstants.LAST_CRAWLED_DATETIME);
        if (lstCrawleddateTime != null) {
            setLastCrawledDateTime(lstCrawleddateTime);
        }

        type = element.getAttribute(SPConstants.STATE_TYPE);
        if (null == type) {
            type = "";
        }
        if (SPConstants.ALERTS_TYPE.equalsIgnoreCase(type)) {
            final NodeList IDsNodeList = element.getElementsByTagName(SPConstants.STATE_EXTRAIDS_ALERTS);
            if ((IDsNodeList != null) && (IDsNodeList.item(0) != null)
                    && (IDsNodeList.item(0).getFirstChild() != null)) {
                extraIDs = new StringBuffer(
                        IDsNodeList.item(0).getFirstChild().getNodeValue());
            }
        } else {
            if (SPConstants.SP2007.equalsIgnoreCase(spType)) {
                setChangeToken(element.getAttribute(SPConstants.STATE_CHANGETOKEN));
                if ((changeToken == null) || (changeToken.length() == 0)) {
                    CachedPrevChangeToken = element.getAttribute(SPConstants.STATE_CACHED_CHANGETOKEN);
                }
                if (SPConstants.CONTENT_FEED.equalsIgnoreCase(feedType)) {
                    if (type != null) {
                        if (canContainFolders()) {
                            NodeList IDsNodeList = element.getElementsByTagName(SPConstants.STATE_EXTRAIDS_FOLDERS);
                            if ((IDsNodeList != null)
                                    && !IDsNodeList.equals("")) {
                                if ((IDsNodeList.item(0) != null)
                                        && (IDsNodeList.item(0).getFirstChild() != null)) {
                                    extraIDs = new StringBuffer(
                                            IDsNodeList.item(0).getFirstChild().getNodeValue());
                                }
                            }
                        }
                        if (canContainAttachments()) {
                            NodeList attchmntsNodeList = element.getElementsByTagName(SPConstants.STATE_EXTRAIDS_ATTACHMENTS);
                            if ((attchmntsNodeList != null)
                                    && !attchmntsNodeList.equals("")) {
                                if ((attchmntsNodeList.item(0) != null)
                                        && (attchmntsNodeList.item(0).getFirstChild() != null)) {
                                    attchmnts = new StringBuffer(
                                            attchmntsNodeList.item(0).getFirstChild().getNodeValue());
                                }
                            }
                        }
                    }

                    try {
                        biggestID = Integer.parseInt(element.getAttribute(SPConstants.STATE_BIGGESTID));
                    } catch (final Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to load Biggest ID for list [ "
                                + listURL + " ]. ", e);
                    }

                    // Load all the delete cache ids from the state file so that
                    // each delete feed can be checked against this list to
                    // avoid sending duplicate delete feeds. Sending duplicate
                    // delete feeds hangs the super GSA. This is very serious so
                    // to avoid the same these ids are required which will be
                    // checked before sending a delete feed
                    String deletedListItemsIDs = element.getAttribute(SPConstants.STATE_DELETED_LIST_ITEMIDS);
                    if (deletedListItemsIDs != null
                            && !deletedListItemsIDs.equals("")) {

                        String[] deletedIds = deletedListItemsIDs.split(SPConstants.HASH);

                        if (deletedIds != null && deletedIds.length > 0) {
                            cachedDeletedIDs = new HashSet<String>();
                            for (String deletedId : deletedIds) {
                                cachedDeletedIDs.add(deletedId);
                            }
                        }

                    }

                }
            }

            // Last Doc Crawled
            final NodeList lastDocCrawledNodeList = element.getElementsByTagName(SPConstants.STATE_LASTDOCCRAWLED);
            if ((lastDocCrawledNodeList != null)
                    && (lastDocCrawledNodeList.item(0) != null)) {
                final Element elemLastDoc = (Element) lastDocCrawledNodeList.item(0);
                final String lastCrawledDocId = elemLastDoc.getAttribute(SPConstants.STATE_ID);
                Calendar lastCrawledDocLastMod = null;
                String lastCrawledDocFolderLevel = null;
                ActionType lastCrawledDocAction = null;

                if (SPConstants.SP2007.equalsIgnoreCase(spType)) {
                    lastCrawledDocFolderLevel = elemLastDoc.getAttribute(SPConstants.STATE_FOLDER_LEVEL);
                    if (SPConstants.CONTENT_FEED.equalsIgnoreCase(feedType)) {
                        lastCrawledDocAction = ActionType.findActionType(elemLastDoc.getAttribute(SPConstants.STATE_ACTION));
                    }
                }
                try {
                    final String strLastCrawledDocLastMod = elemLastDoc.getAttribute(SPConstants.STATE_LASTMODIFIED);
                    lastCrawledDocLastMod = Util.jodaToCalendar(Util.parseDate(strLastCrawledDocLastMod));
                } catch (final Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to load lastCrawledDocLastModified for List [ "
                            + listURL + " ]. ", e);
                }

                // The lastDocument is always used when working with the crawl
                // queue. The one that we want to refer to is the document which
                // was fed with action=ADD. It gives the connector the right
                // reference when making web service calls.
                lastDocProcessedForWS = new SPDocument(lastCrawledDocId,
                        lastCrawledDocLastMod, lastCrawledDocFolderLevel,
                        lastCrawledDocAction);
            }
        }
    }

    /**
     * @return the listURL
     */
    public String getListURL() {
        return listURL;
    }

    /**
     * @param listURL the listURL to set
     */
    public void setListURL(final String listURL) {
        this.listURL = listURL;
    }

    /**
     * @return the changeToken
     */
    public String getChangeToken() {
        return changeToken;
    }

    /**
     * @param inChangeToken the changeToken to set
     */
    public void setChangeToken(final String inChangeToken) {
        CachedPrevChangeToken = changeToken;
        changeToken = inChangeToken;
        LatestCachedChangeToken = changeToken;
        LOGGER.log(Level.CONFIG, "changeToken [ " + changeToken
                + " ], CachedPrevChangeToken [ " + CachedPrevChangeToken
                + " ]. ");
    }

    /**
     * rollback to the last used token;
     */
    public void rollbackToken() {
        LOGGER.log(Level.CONFIG, "Rolling back changeToken [ " + changeToken
                + " ], to CachedPrevChangeToken [ " + CachedPrevChangeToken
                + " ] .... ");
        LatestCachedChangeToken = changeToken;
        changeToken = CachedPrevChangeToken;
        LOGGER.log(Level.CONFIG, "changeToken [ " + changeToken
                + " ], CachedPrevChangeToken [ " + CachedPrevChangeToken
                + " ], LatestChangeToken [ " + LatestCachedChangeToken + " ]. ");
    }

    /**
     * Set the changeToken to its latest value which might have been rolled back
     * to some older value
     */
    public void usingLatestToken() {
        LOGGER.log(Level.CONFIG, "Using latest cached token [ "
                + LatestCachedChangeToken + " ] as change token ..... ");
        changeToken = LatestCachedChangeToken;
        LOGGER.log(Level.CONFIG, "changeToken [ " + changeToken
                + " ], CachedPrevChangeToken [ " + CachedPrevChangeToken
                + " ]. ");
    }

    /**
     * @return the extraIDs
     */
    public StringBuffer getIDs() {
        return extraIDs;
    }

    /**
     * @param ds the extraIDs to set
     */
    public void setIDs(final StringBuffer ds) {
        extraIDs = ds;
    }

    /**
     * Used to store information about folders and items under them. This info
     * is stored in the form of #foldID~foldName#id1#id2/#foldID
     *
     * @param docPath the document path as returned by the web service in the
     *            value of relativeURL field
     * @param docID document ID
     * @param isFolder is the document a folder?
     */
    public void updateExtraIDs(final String docPath, final String docID,
            final boolean isFolder) throws SharepointException {
        if (!Util.isNumeric(docID)) {
            // This must be a list itself. And, we do not bother about list
            // here. We only need list items.
            return;
        }
        if (!canContainFolders()) {
            return;
        }
        if (docPath == null) {
            LOGGER.log(Level.WARNING, "docPath is null. Returning..");
        }

        int index = -1;
        String parentPath = null;
        String docTitle = null;

        index = docPath.indexOf(listConst);
        if (index == -1) {
            LOGGER.log(Level.WARNING, "The document path [ " + docPath
                    + " ] does not match its parent list listConst [ "
                    + listConst + " ], listURL [ " + listURL
                    + "]. returning...");
            return;
        }
        index += listConst.length();
        parentPath = docPath.substring(index);
        index = parentPath.lastIndexOf(SPConstants.SLASH);
        if (index == -1) {
            // this can be a top level folder. We need to update ExtraID, hence
            // make index 0.
            index = 0;
            docTitle = parentPath.substring(index);
        } else {
            docTitle = parentPath.substring(index + 1);
        }

        int idPos = -1;

        final String idPattern = "\\#" + docID + "[\\#|\\~|/]";
        final Pattern pat = Pattern.compile(idPattern);
        final Matcher match = pat.matcher(extraIDs);
        if (match.find()) {
            idPos = match.start();
        }

        if (idPos != -1) {
            // We already know about this ID.
            if (isFolder) {
                int startPos = idPos + 1 + docID.length() + 1;
                int endPos = extraIDs.indexOf("#", startPos);
                int tmp_endPos = extraIDs.indexOf("/", startPos);
                if (tmp_endPos < endPos) {
                    endPos = tmp_endPos;
                }
                extraIDs.replace(startPos, endPos, docTitle);
                LOGGER.log(Level.INFO, "ExtraIDs updated for the folder "
                        + docTitle);
            }
            return;
        }

        parentPath = parentPath.substring(0, index);
        if ((parentPath == null) || parentPath.equals("")) {
            // Case of an item which is not inside any folder. If it is a
            // folder, update the complex string extraIDs. Otherwise, just
            // return, we don't need to store the outer document extraIDs.
            if (isFolder) {
                extraIDs.append("#" + docID + "~" + docTitle + "/#" + docID);
                LOGGER.log(Level.FINEST, "ExtraIDs updated for the folder "
                        + docTitle);
            } else {
                LOGGER.log(Level.FINE, "A top level document is received with docPath [ "
                        + docPath + " ]. ExtraID has not been updated.");
            }
            return;
        }

        // Reach up to the place where the ID is should be inserted. This place
        // will come after some folder entry e.g, #2~Folder__
        final StringTokenizer strTok = new StringTokenizer(parentPath,
                SPConstants.SLASH);
        if (strTok != null) {
            index = 0;
            while (strTok.hasMoreTokens()) {
                final String folder = strTok.nextToken();
                char chr = '#';
                do {
                    index = extraIDs.indexOf("~" + folder, index);
                    if (index == -1) {
                        LOGGER.log(Level.FINE, "A docID [ "
                                + docID
                                + " ] has been found whose parent folder ID is not known. listURL [ "
                                + listURL + " ]. returning...");
                        throw new SharepointException(
                                "extraIDs needs to be updated..");
                    }
                    index += 1 + folder.length();
                    chr = extraIDs.charAt(index);
                } while (chr != '#' && chr != '/');

            }
        }

        extraIDs.insert(index, "#" + docID);
        index += 1 + docID.length();
        if (isFolder) {
            extraIDs.insert(index, "~" + docTitle + "/#" + docID);
        }
        LOGGER.log(Level.FINEST, "ExtraIDs updated for the docID #" + docID
                + " List URL [ " + listURL + " ]. ");
    }

    /**
     * For a given ID, retrieves all the dependednt item extraIDs.
     *
     * @param docID
     * @return the set of dependent extraIDs
     */
    public Set<String> getExtraIDs(final String docID) {
        LOGGER.log(Level.FINEST, "Request for getting all dependent extraIDs for docID #"
                + docID + " ] is received. List URL [ " + listURL + " ]. ");
        final Set<String> depIds = new HashSet<String>();
        if (!Util.isNumeric(docID)) {
            // This must be a list itself. And, we do not bother about list
            // here. We only need list items.
            return depIds;
        }
        if (!canContainFolders() && Util.isNumeric(docID)) {
            depIds.add(docID);
            return depIds;
        }

        int startPos = -1;
        int endPos = -1;

        final String idPattern = "\\#" + docID + "[\\#|\\~|/]";
        Pattern pat = Pattern.compile(idPattern);
        Matcher match = pat.matcher(extraIDs);
        if (match.find()) {
            String idPart = match.group();
            startPos = match.start();
            if (idPart.endsWith("~")) {
                // Case of folder
                endPos = extraIDs.indexOf("/#" + docID);
                endPos += 2 + docID.length();
                idPart = extraIDs.substring(startPos, endPos);

                pat = Pattern.compile("\\#\\d+");
                match = pat.matcher(idPart);
                while (match.find()) {
                    String id = match.group();
                    startPos = match.start();
                    endPos = match.end();
                    if (((startPos > 0) && (idPart.charAt(startPos - 1) == SPConstants.SLASH_CHAR))
                            || ((endPos < idPart.length()) && (idPart.charAt(endPos) == '~'))) {
                        continue;
                        // This is a folder ID and we do not need send any feed
                        // for folders.
                    }
                    id = id.substring(1);
                    if (Util.isNumeric(id)) {
                        depIds.add(id);
                    }
                }
            } else if (Util.isNumeric(docID)) {
                // This is an item inside some folder.
                // Here we are checking certain conditions before adding the
                // original ID as to be sent as the delete feeds.
                // Because, this ID could be of a folder also, and we do not
                // send folder as docs.
                depIds.add(docID);
            }
        } else if (Util.isNumeric(docID)) {
            // This would be a top level doc. We do not make entries in extraIDs
            // for such docs.
            depIds.add(docID);
        }

        return depIds;
    }

    /**
     * Removes the specified ID form the local store
     *
     * @param docID
     */
    public void removeExtraID(final String docID) {
        LOGGER.log(Level.FINEST, "Request to delete docID #" + docID
                + " ] is received. List URL [ " + listURL + " ]. ");
        if (!Util.isNumeric(docID)) {
            // This must be a list itself. And, we do not bother about list
            // here. We only need list items.
            return;
        }
        if (!canContainFolders()) {
            return;
        }
        final String idPattern = "\\#" + docID + "[\\#|\\~|/]";
        final Pattern pat = Pattern.compile(idPattern);
        final Matcher match = pat.matcher(extraIDs);
        if (match.find()) {
            final String idPart = match.group();
            final int startPos = match.start();
            if (!idPart.endsWith("~")) {
                extraIDs.delete(startPos, startPos + 1 + docID.length());
            }
        }
    }

    /**
     * Returns all the stored extraIDs. Used for debugging purposes.
     *
     * @return
     */
    /*
     * public Set getAllExtraIDs() { Set idSet = new HashSet(); String idPattern
     * = "\\#\\d+[\\#|\\~|/]"; Pattern pat = Pattern.compile(idPattern); Matcher
     * match = pat.matcher(extraIDs); while(match.find()) { String idPart =
     * match.group(); Pattern pat2 = Pattern.compile("\\d+"); Matcher match2 =
     * pat2.matcher(idPart); if(match2.find()) { String id = match2.group();
     * if(Util.isNumeric(id)) { idSet.add(id); } } } return idSet; }
     */
    /**
     * This is to keep track of total count of attachments that have been sent
     * for a particular item ID. this info is stored as #itemID|count
     */
    public void updateExtraIDAsAttachment(final String itemID,
            final String attachmentURL) {
        LOGGER.log(Level.FINEST, "Request to update attachment [ "
                + attachmentURL + " ] is received for item ID #" + itemID
                + ". List URL [ " + listURL + " ]. ");
        if (!canContainAttachments() || (attachmentURL == null)) {
            return;
        }
        final Pattern pat = Pattern.compile("\\#" + itemID + "\\|");
        Matcher match = pat.matcher(attachmentURL);
        if (match.find()) {
            LOGGER.log(Level.SEVERE, "attachmentURL [ "
                    + attachmentURL
                    + " matches the pattern #| which is a reserved pattern. returning..");
            return;
        }
        match = pat.matcher(attchmnts);
        if (match.find()) {
            final String newIdPart = "#" + itemID + "|" + attachmentURL + "|";
            attchmnts.replace(match.start(), match.end(), newIdPart);
        } else {
            attchmnts.append("#" + itemID + "|" + attachmentURL);
        }
    }

    /**
     * @param itemID
     * @return set of attachments as SPDocuemnts
     */
    public List<String> getAttachmntURLsFor(final String itemID) {
        LOGGER.log(Level.FINEST, "Request for getting all attachments for item ID #"
                + itemID + " is received. List URL [ " + listURL + " ]. ");
        final List<String> attachmnt_urls = new ArrayList<String>();
        if (!canContainAttachments()) {
            return attachmnt_urls;
        }

        final Pattern delimPat = Pattern.compile("\\#\\d+\\|");
        final Matcher delimMatch = delimPat.matcher(attchmnts);

        final Pattern pat = Pattern.compile("\\#" + itemID + "\\|");
        final Matcher match = pat.matcher(attchmnts);
        if (match.find()) {
            int startPos = -1;
            int endPos = -1;
            startPos = match.end();
            if (delimMatch.find(startPos)) {
                endPos = delimMatch.start();
            } else {
                endPos = attchmnts.length();
            }
            final String urlPart = attchmnts.substring(startPos, endPos);
            final StringTokenizer strTok = new StringTokenizer(urlPart, "|");
            while (strTok.hasMoreTokens()) {
                final String attchmnt = strTok.nextToken();
                if (Util.isURL(attchmnt)) {
                    attachmnt_urls.add(attchmnt);
                }
            }
        }
        return attachmnt_urls;
    }

    /**
     * @param itemID
     * @param attachmentURL
     * @return status of removal
     */
    public boolean removeAttachmntURLFor(final String itemID,
            final String attachmentURL) {
        LOGGER.log(Level.FINEST, "Request for deletion of attachment [ "
                + attachmentURL + " ] is received for item ID #" + itemID
                + ". List URL [ " + listURL + " ]. ");
        if (!canContainAttachments() || (attachmentURL == null)) {
            return false;
        }
        final Pattern pat = Pattern.compile("\\#" + itemID + "\\|.*?"
                + attachmentURL);
        final Matcher match = pat.matcher(attchmnts);
        if (match.find()) {
            final int endPos = match.end();
            final int startPos = endPos - attachmentURL.length();
            attchmnts.delete(startPos, endPos);
            return true;
        }
        return false;
    }

    /**
     * @return the biggestID
     */
    public int getBiggestID() {
        return biggestID;
    }

    /**
     * @param biggestID the biggestID to set
     */
    public void setBiggestID(final int biggestID) {
        this.biggestID = biggestID;
    }

    /**
     * Update the list only if the primary key matches. A list should not be
     * updated with any random list. Update the current ListState with useful
     * information from the coming list, which is the newly discovered version
     * of the list. These information, we do not write into the state file.
     */
    public void updateList(final ListState inList) {
        if (key.equals(inList.getPrimaryKey())) {
            attrs = inList.getAttrs();
            baseTemplate = inList.getBaseTemplate();
            listURL = inList.getListURL();
            parentWebTitle = inList.getParentWebTitle();
            parentWeb = inList.getParentWeb();
            type = inList.getType();
            listTitle = inList.getListTitle();
            listConst = inList.getListConst();
        }
    }

    /**
     * @return the isNewList
     */
    public boolean isNewList() {
        return isNewList;
    }

    /**
     * @param isNewList the isNewList to set
     */
    public void setNewList(final boolean isNewList) {
        this.isNewList = isNewList;
    }

    /**
     * @return the nextPage
     */
    public String getNextPage() {
        return nextPage;
    }

    /**
     * @param nextPage the nextPage to set
     */
    public void setNextPage(final String nextPage) {
        this.nextPage = nextPage;
    }

    /**
     * Checks an itemID if it exists in the local delete cache
     *
     * @param deleteID
     */
    public boolean isInDeleteCache(final String deleteID) {
        if ((cachedDeletedIDs != null) && cachedDeletedIDs.contains(deleteID)) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Adds an item ID into the local delete cache
     *
     * @param deleteID
     */
    public void addToDeleteCache(final String deleteID) {
        if ((cachedDeletedIDs != null) && Util.isNumeric(deleteID)) {
            cachedDeletedIDs.add(deleteID);
        }
    }

    /**
     * Removes an item ID from the locval delete cache
     *
     * @param deleteID
     */
    public void removeFromDeleteCache(final String deleteID) {
        if ((cachedDeletedIDs != null) && Util.isNumeric(deleteID)) {
            cachedDeletedIDs.remove(deleteID);
        }
    }

    /**
     * Clears the local deleted cache store
     */
    public void clearDeleteCache() {
        cachedDeletedIDs = new HashSet<String>();
    }

    /**
     * @return the lastDocument
     */
    public SPDocument getLastDocument() {
        return lastDocument;
    }

    /**
     * setting lastDocCrawled has two effects: 1) doc is remembered. 2) if doc
     * is present in the current crawlQueue, it is removed. It is not an error
     * if doc is NOT present; thus, the client can do either this style: a)
     * process the doc b) remove it from its local crawl queue c)
     * setLastDocCrawled() d) setCrawlQueue() with its local crawl queue -- OR
     * -- a) process the doc b) setLastDocCrawled() c) do
     * getCrawlQueue().first() to get the next doc It is possible, or even
     * likely,that 'doc' is not the first item in the queue. If we get a
     * checkpoint from the Connector Manager, it could be the 100th of a
     * 100-item queue, or the 50th, or in error cases it might even not be IN
     * the queue. So the operation of this method is: 1) make sure the doc is in
     * the queue, and if so: 2) remove everything up to and including the doc.
     *
     * @param lastDocument Content Feed -> If while discovering the docs we have
     *            not yet fetched all the docs duw to the batchhint,
     *            LastCrawlesDoc is set by the getListItemChangesSinceToken. If
     *            we have fetched all the docs, LastCrawledDoc is set at the
     *            time of checkpointing.
     */
    public void setLastDocument(final SPDocument lastDocument) {
        this.lastDocument = lastDocument;
    }

    /**
     * @return the parentWeb
     */
    public String getParentWeb() {
        return parentWeb;
    }

    /**
     * @param parentWeb the parentWeb to set
     */
    public void setParentWeb(final String parentWeb) {
        this.parentWeb = parentWeb;
    }

    /**
     * @return the cachedPrevChangeToken
     */
    public String getCachedPrevChangeToken() {
        return CachedPrevChangeToken;
    }

    /**
     * @param cachedPrevChangeToken the cachedPrevChangeToken to set
     */
    public void setCachedPrevChangeToken(final String cachedPrevChangeToken) {
        CachedPrevChangeToken = cachedPrevChangeToken;
    }

    /**
     * @return the listTitle
     */
    public String getListTitle() {
        return listTitle;
    }

    /**
     * @param type the type to set
     */
    public void setType(final String type) {
        this.type = type;
    }

    /**
     * @return the sahrepoint type of this list
     */
    public String getSharePointType() {
        return spType;
    }

    /**
     * @return the attchmnts
     */
    public StringBuffer getAttchmnts() {
        return attchmnts;
    }

    /**
     * @param attchmnts the attchmnts to set
     */
    public void setAttchmnts(StringBuffer attchmnts) {
        this.attchmnts = attchmnts;
    }

    /**
     * @return the sendListAsDocument
     */
    public boolean isSendListAsDocument() {
        return sendListAsDocument;
    }

    /**
     * @param sendListAsDocument the sendListAsDocument to set
     */
    public void setSendListAsDocument(boolean sendListAsDocument) {
        this.sendListAsDocument = sendListAsDocument;
    }

    /**
     * @return the lastCrawledDateTime
     */
    public String getLastCrawledDateTime() {
        return lastCrawledDateTime;
    }

    /**
     * @param lastCrawledDateTime the lastCrawledDateTime to set
     */
    public void setLastCrawledDateTime(String lastCrawledDateTime) {
        this.lastCrawledDateTime = lastCrawledDateTime;
    }

    /**
     * Checks if all the docs from the crawlqueue have been successfully pulled
     * by CM using nextDocument().
     * <p>
     * Basically it ensures that the last document sent from this list is also
     * the last document in the crawlqueue
     * </p>
     *
     * @return True if the last document sent from this list and the last
     *         document in the list are the same and false otherwise
     */
    public boolean allDocsFed() {
        if (lastDocument != null && crawlQueue != null && crawlQueue.size() > 0) {
            return lastDocument.equals(crawlQueue.get(crawlQueue.size() - 1));
        }
        return false;
    }

    /**
     * Clears all the docs in the crawl queue and also sets lastDocument to null
     * since it has no reference in the crawl queue.
     */
    public void emptyCrawlQueue() {
        if (crawlQueue != null) {
            crawlQueue.clear();
        }
        lastDocument = null;
    }

    /**
     * Returns an iterator to the crawl queue starting from the next position of
     * the index of last document sent to CM. This is required when the list has
     * discovered more docs than the batchhint and hence the CM will fetch them
     * in next batch traversal. Need to point to the correct sublist
     *
     * @return Iterator to the sublist which was not pulled by CM during last
     *         batch traversal
     */
    public Iterator<SPDocument> getCurrentCrawlQueueIterator() {
        if (crawlQueue != null && crawlQueue.size() > 0) {
            int currentDocPos = crawlQueue.indexOf(lastDocument) + 1;
            if (currentDocPos < 0) {
                // This is the case when no docs from this list have been sent
                // even though discovered in some previous batch traversals. So
                // set the current position to be 0. Basically this implies
                // returning an iterator for the entire queue rather than a sub
                // list
                currentDocPos = 0;
            }
            List<SPDocument> docList = crawlQueue.subList(currentDocPos, crawlQueue.size());
            return docList.iterator();
        }

        return null;
    }

    /**
     * @return the lastDocProcessedForWS
     */
    public SPDocument getLastDocProcessedForWS() {
        return lastDocProcessedForWS;
    }

    /**
     * @param lastDocProcessedForWS the lastDocProcessedForWS to set
     */
    public void setLastDocProcessedForWS(SPDocument lastDocProcessedForWS) {
        this.lastDocProcessedForWS = lastDocProcessedForWS;
    }

    @Override
    public String toString() {
        return this.listURL;
    }

}