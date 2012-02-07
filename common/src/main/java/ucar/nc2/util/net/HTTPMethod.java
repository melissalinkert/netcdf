package ucar.nc2.util.net;

import java.io.*;
import java.util.*;

import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpMethodBase;
import org.apache.commons.httpclient.HttpState;
import org.apache.commons.httpclient.URIException;
import org.apache.commons.httpclient.methods.*;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.slf4j.*;
import ucar.unidata.util.EscapeStrings;

/**
 * Created by IntelliJ IDEA.
 * User: dmh
 * Date: Jul 20, 2010
 * Time: 11:44:34 AM
 * To change this template use File | Settings | File Templates.
 */
///////////////////////////////////////////////////////////////////////////////////////////////
/* This class is not thread safe */
public class HTTPMethod
{

    //////////////////////////////////////////////////
    // Define a Retry Handler that supports more retries and is verbose.

    static private org.slf4j.Logger LOG = null;


    static public class RetryHandler extends  org.apache.commons.httpclient.DefaultHttpMethodRetryHandler
    {
        static final int MAXRETRIES = 10;
	static final boolean verbose = false;

        public RetryHandler() {super(MAXRETRIES,false);}
        public boolean retryMethod(final org.apache.commons.httpclient.HttpMethod method,
                                   final IOException exception,
                                   int executionCount)
        {
	    if(verbose) {
	        if(LOG == null) LOG = org.slf4j.LoggerFactory.getLogger(HTTPMethod.class);
		LOG.info(String.format("Retry: count=%d exception=%s\n",executionCount, exception.toString()));
	    }
	    return super.retryMethod(method,exception,executionCount);
        }
    }

    //////////////////////////////////////////////////

    HTTPSession session = null;
    HttpMethodBase method = null; // Current method
    String uri = null;
    List<Header> headers = new ArrayList<Header>();
    HashMap<String,Object> params = new HashMap<String,Object>();
    HttpState context = null;
    boolean executed = false;
    protected boolean closed = false;
    InputStream strm = null;
    RequestEntity content = null;
    HTTPSession.Methods methodclass = null;
    Part[] multiparts = null;

    public HTTPMethod(HTTPSession.Methods m, String uri, HTTPSession session)
            throws HTTPException
    {
        if (uri == null)
            throw new HTTPException("newMethod: no uri specified");
        this.session = session;
        this.uri = uri;

        // Assume uri has already been encoded
        if(false) {// Break off the constraint expression and encode each piece using EscapeStrings.
        String encodeduri =  EscapeStrings.unescapeURL(uri);
        }

        this.methodclass = m;
        switch (this.methodclass) {
        case Put:
            this.method = new PutMethod(uri);
            break;
        case Post:
            this.method = new PostMethod(uri);
            break;
        case Get:
              this.method = new GetMethod(uri);
              break;
        case Head:
            this.method = new HeadMethod(uri);
                break;
        case Options:
            this.method = new OptionsMethod(uri);
               break;
        default:
            this.method = null;
        }
        // Force some actions
        if(method != null) {
            method.setFollowRedirects(true);
            method.setDoAuthentication(true);
        }
    }

    void setcontent()
    {
        switch (this.methodclass) {
        case Put:
            if(this.content != null)
                ((PutMethod)method).setRequestEntity(this.content);
            break;
        case Post:
            if(multiparts != null && multiparts.length > 0) {
                MultipartRequestEntity mre = new MultipartRequestEntity(multiparts,method.getParams());
                ((PostMethod)method).setRequestEntity(mre);
            } else if(this.content != null)
                ((PostMethod)method).setRequestEntity(this.content);
            break;
        case Head:
        case Get:
        case Options:
        default:
            break;
        }
        this.content = null; // do not reuse
        this.multiparts = null;
    }

    public int execute() throws HTTPException
    {
        if (executed)
            throw new HTTPException("Method instance already executed");
        if (uri == null)
            throw new HTTPException("No uri specified");

        try {
            if (headers.size() > 0) {
                for (Header h : headers)
                    method.addRequestHeader(h);
            }
            if (session.globalmethodparams != null) {
                HttpMethodParams hmp = method.getParams();
                for (String key : session.globalmethodparams.keySet())
                    hmp.setParameter(key,session.globalmethodparams.get(key));
            }
            if (params != null) {
                HttpMethodParams hmp = method.getParams();
                for (String key : params.keySet())
                    hmp.setParameter(key,params.get(key));
            }

	    // Change the retry handler
	    method.getParams().setParameter(HttpMethodParams.RETRY_HANDLER,new RetryHandler());

            setcontent();
            try {
                session.sessionClient.executeMethod(method);
            } catch (IllegalArgumentException iae) {
                iae.printStackTrace();
            }
            int code = getStatusCode();
            return code;
        } catch (Exception ie) {
            ie.printStackTrace();
            throw new HTTPException(ie);
        } finally {
            executed = true;
        }
    }

    public void close()
    {
        // try to release underlying resources
        if (closed)
            return;
        if (executed) {
           consumeContent();
        } else
            method.abort();
        method.releaseConnection();
        closed = true;
        session.removeMethod(this);
    }

    public void consumeContent()
    {
        //try {
            //InputStream st = method.getResponseBodyAsStream();
            //while((st.skip(10000) >= 0));
            method.abort();
        //} catch (IOException e) {}
    }

    public void setContext(HttpState cxt)
    {
        session.setContext(cxt);
    }

    public HttpState getContext()
    {
        return session.getContext();
    }

    public int getStatusCode()
    {
        return method == null ? 0 : method.getStatusCode();
    }

    public String getStatusLine()
    {
        return method == null?null:method.getStatusLine().toString();
    }

    public String getRequestLine()
    {
        //fix: return (method == null ? null : method.getRequestLine().toString());
        return "getrequestline not implemented";
    }

    public String getPath()
    {
        try {
            return (method == null ? null : method.getURI().toString());
        } catch (URIException  e) {return null;}
    }

    public boolean canHoldContent()
    {
        if (method == null)
            return false;
        return !(method instanceof HeadMethod);
    }


    public InputStream getResponseBodyAsStream()
    {
            return getResponseAsStream();
    }

    public InputStream getResponseAsStream()
    {
        if (closed)
            return null;
        if (strm != null) {
            try {
                new Exception("Getting MethodStream").printStackTrace();
            } catch (Exception e) {
            }
            ;
            assert strm != null : "attempt to get method stream twice";
        }
        try {
            if (method == null) return null;
            strm = method.getResponseBodyAsStream();
            return strm;
        } catch (Exception e) {
            return null;
        }
    }

    public byte[] getResponseAsBytes(int maxsize)
    {
        if (closed)
            return null;
        byte[] content = getResponseAsBytes();
        if (content.length > maxsize) {
            byte[] limited = new byte[maxsize];
            System.arraycopy(content, 0, limited, 0, maxsize);
            content = limited;
        }
        return content;
    }

    public byte[] getResponseAsBytes()
    {
        if (closed || method == null)
            return null;
        try {
            return method.getResponseBody();
        } catch (Exception e) {
            return null;
        }
    }

    public String getResponseAsString(String charset)
    {
        if (closed || method == null)
            return null;
         try {
            return method.getResponseBodyAsString();
        } catch (Exception e) {
            return null;
         }
    }

    public String getResponseAsString()
    {
         return getResponseAsString("UTF-8");
    }


    public void setMethodHeaders(List<Header> headers) throws HTTPException
    {
        try {
            for (Header h : headers)
                this.headers.add(h);
        } catch (Exception e) {
            throw new HTTPException(e);
        }
    }

    public void setRequestHeader(String name, String value) throws HTTPException
    {
        setRequestHeader(new Header(name, value));
    }

    public void setRequestHeader(Header h) throws HTTPException
    {
        try {
            headers.add(h);
        } catch (Exception e) {
            throw new HTTPException("cause", e);
        }
    }

    public Header getRequestHeader(String name)
    {
          if (method == null)
                return null;
        try {
           return (method.getRequestHeader(name));
        } catch (Exception e) {
            return null;
        }
    }

    public Header[] getRequestHeaders()
    {
         if (method == null)
                return null;
        try {
            Header[] hs = method.getRequestHeaders();
            return hs;
        } catch (Exception e) {
            return null;
        }
    }

    public Header getResponseHeader(String name)
    {
        try {
           return method.getResponseHeader(name);
        } catch (Exception e) {
            return null;
        }
    }

    public Header getResponseHeaderdmh(String name)
    {
        try {

            Header[] headers = getResponseHeaders();
            for (Header h : headers) {
                if (h.getName().equals(name))
                    return h;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public Header[] getResponseHeaders()
    {
        try {
            Header[] hs = method.getResponseHeaders();
            return hs;
        } catch (Exception e) {
            return null;
        }
    }

    public Header[] getResponseFooters()
       {
           try {
               Header[] hs = method.getResponseFooters();
               return hs;
           } catch (Exception e) {
               return null;
           }
       }

    public void setRequestParameter(String name, Object value)
    {
        params.put(name, value);
    }

    public Object getMethodParameter(String key)
    {
        if (method == null)
            return null;
        return method.getParams().getParameter(key);
    }

    public HttpMethodParams getMethodParameters()
    {
        if (method == null)
            return null;
        return method.getParams();
    }
    public Object getResponseParameter(String name)
    {
        if (method == null)
            return null;
        return method.getParams().getParameter(name);
    }


    public void setRequestContentAsString(String content) throws HTTPException
    {
         try {
             this.content = new StringRequestEntity(content,"application/text", "UTF-8");
         }  catch (UnsupportedEncodingException ue) {}
    }

     public void setMultipartRequest(Part[] parts) throws HTTPException
     {
       multiparts = new Part[parts.length];
       for(int i=0;i<parts.length;i++) multiparts[i] = parts[i];
     }

    public String getCharSet()
    {
        return "UTF-8";
    }

    public String getName()
    {
        return method == null ? null : method.getName();
    }

    public String getURI()
    {
        return method == null ? null : method.getPath().toString();
    }

    public String getEffectiveVersion()
    {
        String ver = null;
        if (method != null) {
            ver = method.getEffectiveVersion().toString();
        }
        return ver;
    }


    public String getProtocolVersion()
    {
        return getEffectiveVersion();
    }

    public String getSoTimeout()
    {
        return method == null ? null : ""+method.getParams().getSoTimeout();
    }

    public String getVirtualHost()
    {
        return method == null ? null : method.getParams().getVirtualHost();
    }

    /*public HeaderIterator headerIterator() {
        return new BasicHeaderIterator(getResponseHeaders(), null);
    }*/

    public String getStatusText()
    {
        return getStatusLine();
    }

    public static Enumeration getAllowedMethods()
    {
       Enumeration e = new OptionsMethod().getAllowedMethods();
       return e;
    }

    // Convenience methods to minimize changes elsewhere

    public void setFollowRedirects(boolean tf)
    {
        return; //ignore ; always done
    }

    public String getResponseCharSet()
    {
        return "UTF-8";
    }
}

