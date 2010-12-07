<%@ WebService Language="C#" Class="BulkAuthorization" %>
using System;
using System.Net;
using System.Web.Services;
using Microsoft.SharePoint;
using Microsoft.SharePoint.Utilities;

[WebService(Namespace = "gsbulkauthorization.generated.sharepoint.connector.enterprise.google.com")]
[WebServiceBinding(ConformsTo = WsiProfiles.BasicProfile1_1)]

public class BulkAuthorization : System.Web.Services.WebService
{
    /// <summary>
    /// Authorizes a user against a single authData
    /// </summary>
    /// <param name="authData"></param>
    /// <param name="loginId"></param>
    [WebMethod]
    public void Authorize(AuthData authData, string loginId)
    {
        SPSite site = null;
        SPWeb web = null;
        try
        {
            bool isAlert = false;
            String listURL = authData.listURL;

            SPSecurity.RunWithElevatedPrivileges(delegate()
            {
                // Let's try creating the SPSite object for the incoming URL. If fails, try again by changing the URL format FQDN to Non-FQDN or vice-versa.
                try
                {
                    site = new SPSite(listURL);
                    if (site == null)
                    {
                        site = new SPSite(SwitchURLFormat(listURL));
                    }
                }
                catch (Exception e)
                {
                    site = new SPSite(SwitchURLFormat(listURL));
                }
            });

            web = site.OpenWeb();
            SPPrincipalInfo userInfo = SPUtility.ResolveWindowsPrincipal(site.WebApplication, loginId, SPPrincipalType.All, false);
            if (userInfo == null)
            {
                string logMsg = "Authorization failed because User " + loginId + " can not be resolved into a valid SharePoint user.";
                authData.error += logMsg;
                return;
            }

            // First ensure that the current user has rights to view pages or list items on the web. This will ensure that SPUser object can be constructed for this username.
            bool web_auth = web.DoesUserHavePermissions(userInfo.LoginName, SPBasePermissions.ViewPages | SPBasePermissions.ViewListItems);

            SPUser user = GetSPUser(web, userInfo.LoginName);
            if (user == null)
            {
                authData.error += "User " + loginId + " information not found in the parent site collection of web " + web.Url;
                return;
            }

            if (authData.complexDocId != null && authData.complexDocId.StartsWith("[ALERT]"))
            {
                Guid alert_guid = new Guid(authData.listItemId);
                SPAlert alert = web.Alerts[alert_guid];
                if (alert != null)
                {
                    if (alert.User.LoginName.ToUpper().Equals(user.LoginName.ToUpper()))
                    {
                        authData.isAllowed = true;
                    }
                    else
                    {
                        string SPSystemUser = null;
                        SPSecurity.RunWithElevatedPrivileges(delegate()
                        {
                            SPSystemUser = site.WebApplication.ApplicationPool.Username;
                        });

                        if (SPSystemUser != null && SPSystemUser.ToUpper().Equals(user.LoginName.ToUpper()))
                        {
                            // The logged in user is a SHAREPOINT\\system user
                            if (alert.User.LoginName.ToUpper().Equals("SHAREPOINT\\SYSTEM"))
                            {
                                authData.isAllowed = true;
                            }
                        }
                    }
                }
                else
                {
                    authData.error += "Alert not found. alert_guid [ " + alert_guid + " ] web " + web.Url;
                }
                return;
            }

            SPList list = web.GetListFromUrl(listURL);

            if (authData.listItemId == null || authData.listItemId == "" || authData.listItemId.StartsWith("{"))
            {
                bool isAllowed = list.DoesUserHavePermissions(user, SPBasePermissions.ViewListItems);
                authData.isAllowed = isAllowed;
            }
            else
            {
                int itemId = int.Parse(authData.listItemId);
                SPListItem item = list.GetItemById(itemId);
                bool isAllowed = item.DoesUserHavePermissions(user, SPBasePermissions.ViewListItems);
                authData.isAllowed = isAllowed;
            }
        }
        catch (Exception e)
        {
            string logMsg = "Following error occurred while authorizing user [ " + loginId + " ] against docid [ " + authData.complexDocId + " ] :" + e.Message;
            authData.error += logMsg;
        }
        finally
        {
            if (site != null)
            {
                site.Dispose();
            }
            if (web != null)
            {
                web.Dispose();
            }
        }
    }

    /// <summary>
    /// Calls Authorize for each individual authData
    /// </summary>
    /// <param name="authData"></param>
    /// <param name="loginId"></param>
    /// <returns></returns>
    [WebMethod]
    public AuthData[] BulkAuthorize(AuthData[] authData, string loginId)
    {
        foreach (AuthData ad in authData)
        {
            Authorize(ad, loginId);
        }
        return authData;
    }

    /// <summary>
    /// Checks if this web service can be called
    /// </summary>
    /// <returns></returns>
    [WebMethod]
    public string CheckConnectivity()
    {
        // All the pre-requisites for running this web service should be checked here. 
        // Currently, we are ensuring that RunWithElevatedPrivileges works.
        try
        {
            SPSecurity.RunWithElevatedPrivileges(delegate()
            {
            });
        }
        catch (Exception e)
        {
            return e.Message;
        }
        return "success";
    }

    /// <summary>
    /// There might be some cases when SPWeb.AllUsers can not return the SPUser object. Hence, we must try all the three: AllUsers, SiteUsers and, Users.
    /// Also, these three properties of SPWeb has been accessed in try blocks in the decreasing order of the possibility of success. AllUsers has the highest possibility to succeed.
    /// </summary>
    /// <param name="username"></param>
    /// <returns></returns>
    private SPUser GetSPUser(SPWeb web, string username)
    {
        try
        {
            return web.AllUsers[username];
        }
        catch (Exception e1)
        {
            try
            {
                return web.SiteUsers[username];
            }
            catch (Exception e2)
            {
                try
                {
                    return web.Users[username];
                }
                catch (Exception e3)
                {
                    return null;
                }
            }
        }
    }

    /// <summary>
    /// Switches the URL format between FQDN and Non-FQDN.
    /// </summary>
    /// <param name="url"></param>
    /// <returns></returns>
    private string SwitchURLFormat(string SiteURL)
    {
        Uri url = new Uri(SiteURL);
        string host = url.Host;
        if (host.Contains("."))
        {
            host = host.Split('.')[0];
        }
        else
        {
            IPHostEntry hostEntry = Dns.GetHostEntry(host);
            host = hostEntry.HostName;
        }
        SiteURL = url.Scheme + "://" + host + ":" + url.Port + url.AbsolutePath;
        return SiteURL;
    }
}

/// <summary>
/// The basic authorization unit
/// </summary>
[WebService(Namespace = "BulkAuthorization.generated.sharepoint.connector.enterprise.google.com")]
[WebServiceBinding(ConformsTo = WsiProfiles.BasicProfile1_1)]
[Serializable]
public class AuthData
{
    public String listURL
    {
        get { return _listURL; }
        set { _listURL = value; }
    }
    public String listItemId
    {
        get { return _listItemId; }
        set { _listItemId = value; }
    }
    public Boolean isAllowed
    {
        get { return _isAllowed; }
        set { _isAllowed = value; }
    }
    public String error
    {
        get { return _error; }
        set { _error = value; }
    }
    public String complexDocId
    {
        get { return _complexDocId; }
        set { _complexDocId = value; }
    }
    private string _listURL;
    private string _listItemId;
    private bool _isAllowed;
    private string _error;
    private string _complexDocId;
}