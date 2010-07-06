// Copyright 2007 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.sharepoint.client;

import com.google.enterprise.connector.sharepoint.Util;
import com.google.enterprise.connector.sharepoint.generated.ListsStub;
import com.google.enterprise.connector.sharepoint.generated.ListsStub.GetAttachmentCollection;
import com.google.enterprise.connector.sharepoint.generated.ListsStub.GetListItemChanges;
import com.google.enterprise.connector.sharepoint.generated.ListsStub.GetListItems;
import com.google.enterprise.connector.spi.RepositoryException;
import com.google.enterprise.connector.spi.SimpleValue;

import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMAttribute;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMFactory;
import org.apache.axis2.AxisFault;

import java.rmi.RemoteException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import javax.xml.namespace.QName;

/**
 * This class holds data and methods for any call to Lists Web Service.
 *
 */
public class ListsWS {
  private static final String listsEndpoint = "_vti_bin/Lists.asmx";
  private SharepointClientContext sharepointClientContext;
  private String endpoint;
  private ListsStub stub;
  
  public ListsWS(SharepointClientContext sharepointClientContext) 
      throws SharepointException, RepositoryException {
    this.sharepointClientContext = sharepointClientContext;
    endpoint = "http://" + sharepointClientContext.getHost() + ":" + 
        sharepointClientContext.getPort() + 
        Util.getEscapedSiteName(sharepointClientContext.getsiteName()) + 
        listsEndpoint;
    try {
      stub = new ListsStub(endpoint);
      sharepointClientContext.setStubWithAuth(stub, endpoint);
    } catch (AxisFault e) {
      throw new SharepointException(e.toString());        
    }
  }
  
  public ListsWS(SharepointClientContext sharepointClientContext, 
      String siteName) throws SharepointException, RepositoryException {
  this.sharepointClientContext = sharepointClientContext;
  if (siteName.startsWith("http://")) {
    siteName = siteName.substring(7);
    endpoint = "http://" + Util.getEscapedSiteName(siteName) + listsEndpoint;
  } else {
    endpoint = Util.getEscapedSiteName(siteName) + listsEndpoint;
  }
  try {
    stub = new ListsStub(endpoint);
    sharepointClientContext.setStubWithAuth(stub, endpoint);
  } catch (AxisFault e) {
    throw new SharepointException(e.toString());
  }     
}
  
  /**
   * Gets all the list items of a particular list
   * @param listName internal name of the list
   * @return list of sharepoint documents corresponding to items in the list.
   * @throws SharepointException 
   */
  public List getListItems(String listName) throws SharepointException {
    ArrayList<SPDocument> listItems = new ArrayList<SPDocument>();
    String urlPrefix = "http://" + sharepointClientContext.getHost() + ":" + 
        sharepointClientContext.getPort() + "/";
      
    ListsStub.GetListItems req = new ListsStub.GetListItems();
    req.setListName(listName);
    req.setQuery(null);
    
    req.setViewFields(null);
    req.setRowLimit("");
    req.setViewName("");
    req.setWebID("");
    
    /* Setting query options to be Recursive, so that docs under folders are
     * retrieved recursively
     */
    ListsStub.QueryOptions_type36 queryOptions = 
        new ListsStub.QueryOptions_type36();
    req.setQueryOptions(queryOptions);
    OMFactory omfactory = OMAbstractFactory.getOMFactory();                  
    OMElement options = omfactory.createOMElement("QueryOptions", null);
    queryOptions.setExtraElement(options);
    OMElement va = omfactory.createOMElement("ViewAttributes", null,
            options);
    OMAttribute attr = omfactory.createOMAttribute("Scope", null,
            "Recursive");    
    va.addAttribute(attr);
    
    /* Setting the query so that the returned items are in lastModified 
     * order. 
     */    
    ListsStub.Query_type34 query = new ListsStub.Query_type34();
    req.setQuery(query);
    
    OMElement queryOM = omfactory.createOMElement("Query", null);
    query.setExtraElement(queryOM);
    OMElement orderBy = omfactory.createOMElement("OrderBy", null, queryOM);
    orderBy.addChild(omfactory.createOMText(orderBy, "ows_Modified"));
    
    try {
      ListsStub.GetListItemsResponse res = stub.GetListItems(req);
      OMFactory omf = OMAbstractFactory.getOMFactory();
      OMElement oe = res.getGetListItemsResult().getOMElement
          (GetListItems.MY_QNAME, omf);
      System.out.println(oe.toString());
      StringBuffer url = new StringBuffer();
      for (Iterator<OMElement> ita = oe.getChildElements(); ita.hasNext(); ) {
        OMElement resultOmElement = ita.next();
        Iterator<OMElement> resultIt = resultOmElement.getChildElements();
        OMElement dataOmElement = resultIt.next();
        for (Iterator<OMElement> dataIt = dataOmElement.getChildElements();
            dataIt.hasNext(); ) {
          OMElement rowOmElement = dataIt.next();            
          if (rowOmElement.getAttribute(new QName("ows_FileRef")) != null) {
            String docId = rowOmElement.getAttribute(
                new QName("ows_UniqueId")).getAttributeValue();  
            String lastModified = rowOmElement.getAttribute(
                new QName("ows_Modified")).getAttributeValue();
            String fileName = rowOmElement.getAttribute(
                new QName("ows_FileRef")).getAttributeValue();
            fileName = fileName.substring(fileName.indexOf("#") + 1);
            url.setLength(0);
            url.append(urlPrefix);
            url.append(fileName);              
                          
            try {
              SPDocument doc;
              doc = new SPDocument(docId, url.toString(), 
                  Util.listItemsStringToCalendar(lastModified));             
              listItems.add(doc);
            } catch (ParseException e) {
              throw new SharepointException(e.toString(), e);
            }                         
          }
        }
      }
    } catch (RemoteException e) {
      throw new SharepointException(e.toString(), e);
    }     
    return listItems;
  }
  
  /**
   * Gets all the list item changes of a particular generic list since 
   * a particular time. Generic lists include Discussion boards, Calendar,
   * Tasks, Links, Announcements.
   * @param list BaseList object
   * @return list of sharepoint SPDocuments corresponding to items in the list. 
   * These are ordered by last Modified time.
   * @throws SharepointException 
   */
  public List getGenericListItemChanges(BaseList list, Calendar since) 
      throws SharepointException {
    String listName = list.getInternalName();
    ArrayList<SPDocument> listItems = new ArrayList<SPDocument>();
    String urlPrefix = "http://" + sharepointClientContext.getHost() + ":" + 
    sharepointClientContext.getPort() 
    + sharepointClientContext.getsiteName() + "/" + "Lists" + "/" 
    + list.getTitle() + "/" + "DispForm.aspx?ID=";
    ListsStub.GetListItemChanges req = new ListsStub.GetListItemChanges();
    req.setListName(listName);
    req.setViewFields(null);   
    if (since != null) {
      req.setSince(SimpleValue.calendarToIso8601(since));
    } else {
      req.setSince(null);      
    }
    try {
      ListsStub.GetListItemChangesResponse res = stub.GetListItemChanges(req);
      OMFactory omf = OMAbstractFactory.getOMFactory();
      OMElement oe = res.getGetListItemChangesResult().getOMElement
          (GetListItemChanges.MY_QNAME, omf);
      StringBuffer url = new StringBuffer();
      for (Iterator<OMElement> ita = oe.getChildElements(); ita.hasNext(); ) {
        OMElement resultOmElement = ita.next();
        Iterator<OMElement> resultIt = resultOmElement.getChildElements();
        OMElement dataOmElement = resultIt.next();
        for (Iterator<OMElement> dataIt = dataOmElement.getChildElements();
            dataIt.hasNext(); ) {
          OMElement rowOmElement = dataIt.next();            
          
          String docId = rowOmElement.getAttribute(
              new QName("ows_UniqueId")).getAttributeValue();
          String itemId = rowOmElement.getAttribute(
              new QName("ows_ID")).getAttributeValue();     
          url.setLength(0);
          url.append(urlPrefix);
          url.append(itemId);                                
          SPDocument doc;
          doc = new SPDocument(docId, url.toString(), list.getLastMod());
          listItems.add(doc);                                  
        }
      }
      Collections.sort(listItems);
    } catch (RemoteException e) {
      throw new SharepointException(e.toString(), e);
    }     
    return listItems;
  }  
  
  /**
   * Gets all the list item changes of a particular SPDocument library since 
   * a particular time.
   * @param list BaseList object
   * @return list of sharepoint SPDocuments corresponding to items in the list. 
   * These are ordered by last Modified time.
   * @throws SharepointException 
   */
  public List getDocLibListItemChanges(BaseList list, Calendar since) 
      throws SharepointException {
    String listName = list.getInternalName();
    ArrayList<SPDocument> listItems = new ArrayList<SPDocument>();
    String urlPrefix = "http://" + sharepointClientContext.getHost() + ":" + 
    sharepointClientContext.getPort() + "/";
    ListsStub.GetListItemChanges req = new ListsStub.GetListItemChanges();
    req.setListName(listName);
    req.setViewFields(null);   
    if (since != null) {
      req.setSince(SimpleValue.calendarToIso8601(since));
    } else {
      req.setSince(null);      
    }
    try {
      ListsStub.GetListItemChangesResponse res = stub.GetListItemChanges(req);
      OMFactory omf = OMAbstractFactory.getOMFactory();
      OMElement oe = res.getGetListItemChangesResult().getOMElement
          (GetListItemChanges.MY_QNAME, omf);
      StringBuffer url = new StringBuffer();
      for (Iterator<OMElement> ita = oe.getChildElements(); ita.hasNext(); ) {
        OMElement resultOmElement = ita.next();
        Iterator<OMElement> resultIt = resultOmElement.getChildElements();
        OMElement dataOmElement = resultIt.next();
        for (Iterator<OMElement> dataIt = dataOmElement.getChildElements();
            dataIt.hasNext(); ) {
          OMElement rowOmElement = dataIt.next();            
          if (rowOmElement.getAttribute(new QName("ows_FileRef")) != null) {
            String docId = rowOmElement.getAttribute(
                new QName("ows_UniqueId")).getAttributeValue();  
            String lastModified = rowOmElement.getAttribute(
                new QName("ows_Modified")).getAttributeValue();
            String fileName = rowOmElement.getAttribute(
                new QName("ows_FileRef")).getAttributeValue();
            /*
             * An example of ows_FileRef is 
             * 1;#unittest/Shared SPDocuments/sync.doc 
             * We need to get rid of 1;#
             */
            fileName = fileName.substring(fileName.indexOf("#") + 1);                    
            url.setLength(0);
            url.append(urlPrefix);
            url.append(fileName);    
            String metaInfo = rowOmElement.getAttribute(
                new QName("ows_MetaInfo")).getAttributeValue();
            String objType = rowOmElement.getAttribute(
                new QName("ows_FSObjType")).getAttributeValue();
            String[] arrayOfMetaInfo = metaInfo.split("\n");
            String author = null;
            for (String authorMeta : arrayOfMetaInfo) {
              if (authorMeta.startsWith("vti_author")) {
                author = authorMeta.substring
                    (authorMeta.indexOf(":") + 1).trim();                                
              }
            }            
            try {
              SPDocument doc;
              doc = new SPDocument(docId, url.toString(), 
                  Util.listItemChangesStringToCalendar(lastModified));
              doc.setObjType(objType);
              if (author != null) {
                doc.setAuthor(author);
              }
              listItems.add(doc);
            } catch (ParseException e) {
              throw new SharepointException(e.toString(), e);
            }                         
          }
        }
      }
      Collections.sort(listItems);
    } catch (RemoteException e) {
      throw new SharepointException(e.toString(), e);
    }     
    return listItems;
  }
  
  /**
   * Gets all the attachments of a particular list item.
   * @param baseList List to which the item belongs
   * @param listItem list item for which the attachments need to be retrieved.
   * @return list of sharepoint SPDocuments corresponding to attachments
   * for the given list item. 
   * These are ordered by last Modified time.
   * @throws SharepointException 
   */
  public List getAttachments(BaseList baseList, SPDocument listItem) 
      throws SharepointException {
    String listName = baseList.getInternalName();
    /*
     * An example of docId is 3;#{BC0E981B-FAA5-4476-A44F-83EA27155513}.
     * For listItemId, we need to pass "3". 
     */
   
    String arrayOflistItemId[] = listItem.getDocId().split(";#");
    String listItemId = arrayOflistItemId[0];
    ArrayList<SPDocument> listAttachments = new ArrayList<SPDocument>();
    ListsStub.GetAttachmentCollection req = 
        new ListsStub.GetAttachmentCollection();
    req.setListName(listName);
    req.setListItemID(listItemId);
    try {
      ListsStub.GetAttachmentCollectionResponse res = 
          stub.GetAttachmentCollection(req);
      OMFactory omf = OMAbstractFactory.getOMFactory();
      OMElement oe = res.getGetAttachmentCollectionResult().getOMElement
          (GetAttachmentCollection.MY_QNAME, omf);
      Iterator<OMElement> ita = oe.getChildElements();
      OMElement attachmentsOmElement = ita.next();
      Iterator<OMElement> attachmentsIt =
          attachmentsOmElement.getChildElements();      
      for (Iterator<OMElement> attachmentIt = 
          attachmentsOmElement.getChildElements(); attachmentsIt.hasNext();) {
        OMElement attachmentOmElement = attachmentsIt.next();        
        String url = attachmentOmElement.getText();               
        SPDocument doc;
        doc = new SPDocument(url, url, baseList.getLastMod());        
        listAttachments.add(doc);                
      }
      Collections.sort(listAttachments);
    } catch (RemoteException e) {
      throw new SharepointException(e.toString(), e);
    }
    return listAttachments;
  }
}