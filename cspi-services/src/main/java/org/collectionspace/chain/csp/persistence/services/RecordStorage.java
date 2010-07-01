package org.collectionspace.chain.csp.persistence.services;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.collectionspace.chain.csp.persistence.services.connection.ConnectionException;
import org.collectionspace.chain.csp.persistence.services.connection.RequestMethod;
import org.collectionspace.chain.csp.persistence.services.connection.ReturnedDocument;
import org.collectionspace.chain.csp.persistence.services.connection.ReturnedMultipartDocument;
import org.collectionspace.chain.csp.persistence.services.connection.ReturnedURL;
import org.collectionspace.chain.csp.persistence.services.connection.ServicesConnection;
import org.collectionspace.chain.csp.persistence.services.vocab.URNProcessor;
import org.collectionspace.chain.csp.schema.FieldSet;
import org.collectionspace.chain.csp.schema.Record;
import org.collectionspace.chain.csp.schema.Field;
import org.collectionspace.chain.util.json.JSONUtils;
import org.collectionspace.csp.api.core.CSPRequestCache;
import org.collectionspace.csp.api.core.CSPRequestCredentials;
import org.collectionspace.csp.api.persistence.ExistException;
import org.collectionspace.csp.api.persistence.Storage;
import org.collectionspace.csp.api.persistence.UnderlyingStorageException;
import org.collectionspace.csp.api.persistence.UnimplementedException;
import org.collectionspace.csp.helper.persistence.ContextualisedStorage;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;

import org.dom4j.Node;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RecordStorage implements ContextualisedStorage {
	private static final Logger log=LoggerFactory.getLogger(RecordStorage.class);
	private ServicesConnection conn;
	private Record r;
	private Map<String,String> view_good=new HashMap<String,String>();// map of servicenames of fields to descriptors
	private Map<String,String> view_map=new HashMap<String,String>(); // map of csid to service name of field
	private Set<String> xxx_view_deurn=new HashSet<String>();

	public RecordStorage(Record r,ServicesConnection conn) throws DocumentException, IOException {	
		this.conn=conn;
		this.r=r;
		// Number
		if(r.getMiniNumber()!=null){
			view_good.put("number",r.getMiniNumber().getID());
			view_map.put(r.getMiniNumber().getServicesTag(),r.getMiniNumber().getID());
			if(r.getMiniNumber().hasAutocompleteInstance())
				xxx_view_deurn.add(r.getMiniNumber().getID());
		}
		// Summary
		if(r.getMiniSummary() !=null){
			view_good.put("summary",r.getMiniSummary().getID());
			view_map.put(r.getMiniSummary().getServicesTag(),r.getMiniSummary().getID());
			if(r.getMiniSummary().hasAutocompleteInstance())
				xxx_view_deurn.add(r.getMiniSummary().getID());
		}
		//Summary list
		if(r.getAllMiniSummaryList().length > 0){
			for(FieldSet fs : r.getAllMiniSummaryList()) {
				view_good.put("summarylist_"+ fs.getID(),fs.getID());
				view_map.put(fs.getServicesTag(),fs.getID());
				if(fs instanceof Field) {
					// Single field
					Field f=(Field)fs;
					if(f.hasAutocompleteInstance()){
						xxx_view_deurn.add(f.getID());
					}
				}
			}
		}
	}

	/**
	 * Set the csids that were retrieved in the cache
	 * @param cache The cache itself
	 * @param path The path to the object on the service layer
	 * @param key The key for the node in the json file
	 * @param value The value for the node in the json file
	 */
	private void setGleanedValue(CSPRequestCache cache,String path,String key,String value) {
		cache.setCached(getClass(),new String[]{"glean",path,key},value);
	}

	/**
	 * Get a value out of the cache
	 * @param {CSPRequestCache} cache The cache in which we store the csids
	 * @param {String} path The path to the record 
	 * @param {String} key The key to recreate the unique key to retrieve the cached value
	 * @return {String} The csid that was stored 
	 */
	private String getGleanedValue(CSPRequestCache cache,String path,String key) {
		return (String)cache.getCached(getClass(),new String[]{"glean",path,key});
	}

	/**
	 * Convert an XML file into a JSON string
	 * @param {JSONObject} out The JSON string to which the XML has been converted
	 * @param {Document} in The XML document that has to be converted
	 * @throws JSONException
	 */
	private void convertToJson(JSONObject out,Document in) throws JSONException {
		XmlJsonConversion.convertToJson(out,r,in);
	}

	/**
	 * Convert the JSON from the UI Layer into XML for the Service layer while using the XML structure from default.xml
	 * Send the XML through to the Service Layer to store it in the database
	 * The Service Layer returns a url to the object we just stored.
	 * @param {ContextualisedStorage} root 
	 * @param {CSPRequestCredentials} creds
	 * @param {CSPRequestCache} cache
	 * @param {String} filePath part of the path to the Service URL (containing the type of object)
	 * @param {JSONObject} jsonObject The JSON string coming in from the UI Layer, containing the object to be stored
	 * @return {String} csid The id of the object in the database
	 */
	public String autocreateJSON(ContextualisedStorage root,CSPRequestCredentials creds,CSPRequestCache cache,String filePath, JSONObject jsonObject) throws ExistException, UnimplementedException, UnderlyingStorageException {
		try {
			Map<String,Document> parts=new HashMap<String,Document>();
			Document doc = null;
			for(String section : r.getServicesRecordPaths()) {
				String path=r.getServicesRecordPath(section);
				String[] record_path=path.split(":",2);
				doc=XmlJsonConversion.convertToXml(r,jsonObject,section);
				parts.put(record_path[0],doc);
			}
			ReturnedURL url;
			//some records are accepted as multipart in the service layers, others arent, that's why we split up here
			if(r.isMultipart())
				url = conn.getMultipartURL(RequestMethod.POST,r.getServicesURL()+"/",parts,creds,cache);
			else
				url = conn.getURL(RequestMethod.POST, r.getServicesURL()+"/", doc, creds, cache);
			if(url.getStatus()>299 || url.getStatus()<200)
				throw new UnderlyingStorageException("Bad response "+url.getStatus());
			return url.getURLTail();
		} catch (ConnectionException e) {
			throw new UnderlyingStorageException("Service layer exception",e);
		} catch (JSONException e) {
			throw new UnimplementedException("JSONException",e);
		}
	}

	public void createJSON(ContextualisedStorage root,CSPRequestCredentials creds,CSPRequestCache cache,String filePath, JSONObject jsonObject)
	throws ExistException, UnimplementedException, UnderlyingStorageException {
		throw new UnimplementedException("Cannot post to full path");
	}

	public void deleteJSON(ContextualisedStorage root,CSPRequestCredentials creds,CSPRequestCache cache,String filePath) throws ExistException,
	UnimplementedException, UnderlyingStorageException {
		try {
			int status=conn.getNone(RequestMethod.DELETE,r.getServicesURL()+"/"+filePath,null,creds,cache);
			if(status>299 || status<200) // XXX CSPACE-73, should be 404
				throw new UnderlyingStorageException("Service layer exception status="+status);
		} catch (ConnectionException e) {
			throw new UnderlyingStorageException("Service layer exception",e);
		}		
	}

	private String xxx_deurn(String in) throws UnderlyingStorageException {
		if(!in.startsWith("urn:"))
			return in;
		if(!in.endsWith("'"))
			return in;
		in=in.substring(0,in.length()-1);
		int pos=in.lastIndexOf("'");
		if(pos==-1)
			return in+"'";
		try {
			return URLDecoder.decode(in.substring(pos+1),"UTF8");
		} catch (UnsupportedEncodingException e) {
			throw new UnderlyingStorageException("No UTF8!");
		}
	}

	/**
	 * Gets a list of csids of a certain type of record together with the pagination info
	 */
	@SuppressWarnings("unchecked")
	public JSONObject getPathsJSON(ContextualisedStorage root,CSPRequestCredentials creds,CSPRequestCache cache,String rootPath,JSONObject restrictions) throws ExistException, UnimplementedException, UnderlyingStorageException {
		try {
			Document list=null;
			JSONObject out = new JSONObject();
			JSONObject pagination = new JSONObject();
			List<String> listitems=new ArrayList<String>();
			String postfix = "?";
			if(restrictions!=null){
				if(restrictions.has("keywords")) {
					/* Keyword search */
					String data=URLEncoder.encode(restrictions.getString("keywords"),"UTF-8");
					postfix += "kw="+data+"&";
				} 
				if(restrictions.has("pageSize")){
					postfix += "pgSz="+restrictions.getString("pageSize")+"&";
				}
				if(restrictions.has("pageNum")){
					postfix += "pgNum="+restrictions.getString("pageNum")+"&";
				}
			}
			postfix = postfix.substring(0, postfix.length()-1);
			if(postfix.length() == 0){postfix +="/";}
			ReturnedDocument all = conn.getXMLDocument(RequestMethod.GET,r.getServicesURL()+postfix,null,creds,cache);
			if(all.getStatus()!=200){
				throw new ConnectionException("Bad request during identifier cache map update: status not 200");
			}
			list=all.getDocument();
			
			List<Node> nodes=list.selectNodes("/"+r.getServicesListPath().split("/")[0]+"/*");
			if(r.getServicesListPath().equals("roles_list/*") || r.getServicesListPath().equals("permissions_list/*")){
				//XXX hack to deal with roles being inconsistent
				// XXX CSPACE-1887 workaround
				for(Node node : nodes) {
					if(node.matches("/"+r.getServicesListPath())){
						String csid = node.valueOf( "@csid" );
						listitems.add(csid);
					}
					else{
						pagination.put(node.getName(), node.getText());
					}
				}
			}
			else{
				for(Node node : nodes) {
					if(node.matches("/"+r.getServicesListPath())){
						List<Node> fields=node.selectNodes("*");
						String csid=node.selectSingleNode("csid").getText();
						for(Node field : fields) {
							if("csid".equals(field.getName())) {
								int idx=csid.lastIndexOf("/");
								if(idx!=-1)
									csid=csid.substring(idx+1);
								listitems.add(csid);
							} else if("uri".equals(field.getName())) {
							// Skip!
							} else {
								String json_name=view_map.get(field.getName());
								if(json_name!=null) {
									String value=field.getText();
									// XXX hack to cope with multi values		
									if(value==null || "".equals(value)) {
										List<Node> inners=field.selectNodes("*");
										for(Node n : inners) {
											value+=n.getText();
										}
									}
									setGleanedValue(cache,r.getServicesURL()+"/"+csid,json_name,value);
								}
							}
						}
					}
					else{
						pagination.put(node.getName(), node.getText());
					}
				}
			}
			out.put("pagination", pagination);
			out.put("listItems", listitems.toArray(new String[0]));
			return out;
		} catch (ConnectionException e) {
			throw new UnderlyingStorageException("Service layer exception",e);
		} catch (UnsupportedEncodingException e) {
			throw new UnderlyingStorageException("Service layer exception",e);
		} catch (JSONException e) {
			throw new UnderlyingStorageException("Service layer exception",e);
		}
	}

	/**
	 * Gets a list of csids of a certain type of record
	 */
	@SuppressWarnings("unchecked")
	public String[] getPaths(ContextualisedStorage root,CSPRequestCredentials creds,CSPRequestCache cache,String rootPath,JSONObject restrictions) throws ExistException, UnimplementedException, UnderlyingStorageException {
		try {
			Document list=null;
			List<String> out=new ArrayList<String>();
			String postfix = "?";
			if(restrictions!=null){
				if(restrictions.has("keywords")) {
					/* Keyword search */
					String data=URLEncoder.encode(restrictions.getString("keywords"),"UTF-8");
					postfix += "kw="+data+"&";
				} 
				if(restrictions.has("pageSize")){
					postfix += "pgSz="+restrictions.getString("pageSize")+"&";
				}
				if(restrictions.has("pageNum")){
					postfix += "pgNum="+restrictions.getString("pageNum")+"&";
				}
			}
			postfix = postfix.substring(0, postfix.length()-1);
			if(postfix.length() == 0){postfix +="/";}
			ReturnedDocument all = conn.getXMLDocument(RequestMethod.GET,r.getServicesURL()+postfix,null,creds,cache);
			if(all.getStatus()!=200){
				throw new ConnectionException("Bad request during identifier cache map update: status not 200");
			}
			list=all.getDocument();
			List<Node> objects=list.selectNodes(r.getServicesListPath());
			if(r.getServicesListPath().equals("roles_list/*")){
				//XXX hack to deal with roles being inconsistent
				// XXX CSPACE-1887 workaround
				for(Node object : objects) {
					String csid = object.valueOf( "@csid" );
					out.add(csid);
				}
				
			}
			else{
				for(Node object : objects) {
					List<Node> fields=object.selectNodes("*");
					String csid=object.selectSingleNode("csid").getText();
					for(Node field : fields) {
						if("csid".equals(field.getName())) {
							int idx=csid.lastIndexOf("/");
							if(idx!=-1)
								csid=csid.substring(idx+1);
							out.add(csid);						
						} else if("uri".equals(field.getName())) {
							// Skip!
						} else {
							String json_name=view_map.get(field.getName());
							if(json_name!=null) {
								String value=field.getText();
								// XXX hack to cope with multi values		
								if(value==null || "".equals(value)) {
									List<Node> inners=field.selectNodes("*");
									for(Node n : inners) {
										value+=n.getText();
									}
								}
								setGleanedValue(cache,r.getServicesURL()+"/"+csid,json_name,value);
							}
						}
					}
				}
			}
			//log.info(out.toString());
			return out.toArray(new String[0]);
		} catch (ConnectionException e) {
			throw new UnderlyingStorageException("Service layer exception",e);
		} catch (UnsupportedEncodingException e) {
			throw new UnderlyingStorageException("Service layer exception",e);
		} catch (JSONException e) {
			throw new UnderlyingStorageException("Service layer exception",e);
		}
	}

	public JSONObject retrieveJSON(ContextualisedStorage root,CSPRequestCredentials creds,CSPRequestCache cache,String filePath) throws ExistException,
	UnimplementedException, UnderlyingStorageException {
		try {
			String[] parts=filePath.split("/",2);
			if(parts.length==2) {
				return viewRetrieveJSON(root,creds,cache,parts[0],parts[1]);
			} else
				return simpleRetrieveJSON(creds,cache,filePath);
		} catch(JSONException x) {
			throw new UnderlyingStorageException("Error building JSON",x);
		}
	}

	public JSONObject viewRetrieveJSON(ContextualisedStorage storage,CSPRequestCredentials creds,CSPRequestCache cache,String filePath,String view) throws ExistException,UnimplementedException, UnderlyingStorageException, JSONException {
		if("view".equals(view))
			return miniViewRetrieveJSON(cache,creds,filePath);
		else if("refs".equals(view))
			return refViewRetrieveJSON(storage,creds,cache,filePath);
		else
			return new JSONObject();
	}

	// XXX support URNs for reference
	private JSONObject miniForURI(ContextualisedStorage storage,CSPRequestCredentials creds,CSPRequestCache cache,String refname,String uri) throws ExistException, UnimplementedException, UnderlyingStorageException, JSONException {
		return storage.retrieveJSON(storage,creds,cache,"direct/urn/"+uri+"/"+refname);
	}

	public JSONObject refViewRetrieveJSON(ContextualisedStorage storage,CSPRequestCredentials creds,CSPRequestCache cache,String filePath) throws ExistException,UnimplementedException, UnderlyingStorageException, JSONException {
		try {
			JSONObject out=new JSONObject();
			//not all the records need a reference, look in default.xml for which that don't
			if(r.hasTermsUsed()){
				String path = r.getServicesURL()+"/"+filePath+"/authorityrefs";
				ReturnedDocument all = conn.getXMLDocument(RequestMethod.GET,path,null,creds,cache);
				if(all.getStatus()!=200)
					throw new ConnectionException("Bad request during identifier cache map update: status not 200");
				Document list=all.getDocument();
				for(Object node : list.selectNodes("authority-ref-list/authority-ref-item")) {
					if(!(node instanceof Element))
						continue;
					String key=((Element)node).selectSingleNode("sourceField").getText();
					String uri=((Element)node).selectSingleNode("uri").getText();
					String refname=((Element)node).selectSingleNode("refName").getText();
					String fieldName = key.split(":")[1];
					Field fieldinstance = (Field)r.getRepeatField(fieldName);
					
					if(uri!=null && uri.startsWith("/"))
						uri=uri.substring(1);
					JSONObject data=miniForURI(storage,creds,cache,refname,uri);
					data.put("sourceFieldselector", fieldinstance.getSelector());
					data.put("sourceFieldName", fieldName);
					data.put("sourceFieldType", r.getID());
					out.put(key,data);
				}
			}
			return out;
		} catch (ConnectionException e) {
			throw new UnderlyingStorageException("Connection problem",e);
		}
	}

	public JSONObject miniViewRetrieveJSON(CSPRequestCache cache,CSPRequestCredentials creds,String filePath) throws ExistException,UnimplementedException, UnderlyingStorageException, JSONException {
		JSONObject out=new JSONObject();
		JSONObject summarylist=new JSONObject();
		String summarylistname = "summarylist_";
		Set<String> to_get=new HashSet<String>(view_good.keySet());
		// Try to fullfil from gleaned info
		//gleaned is info that everytime we read a record we cache certain parts of it
		for(String fieldname : view_good.keySet()) {
			String good = view_good.get(fieldname);
			String gleaned=getGleanedValue(cache,r.getServicesURL()+"/"+filePath,good);
			if(gleaned==null)
				continue;
			if(xxx_view_deurn.contains(good))
				gleaned=xxx_deurn(gleaned);
			
			String name = fieldname;
			if(name.startsWith(summarylistname)){
				name = name.substring(summarylistname.length());
				summarylist.put(name, gleaned);
			}
			else{
				out.put(fieldname,gleaned);
			}
			to_get.remove(fieldname);
		}
		// Do a full request
		if(to_get.size()>0) {
			JSONObject data=simpleRetrieveJSON(creds,cache,filePath);
			for(String fieldname : to_get) {
				String good = view_good.get(fieldname);
				//this might work with repeat objects
				String value = JSONUtils.checkKey(data, good);
				if(value != null){
					String vkey=fieldname;
					if(xxx_view_deurn.contains(good))
						value=xxx_deurn(value);
				
					if(vkey.startsWith(summarylistname)){
						String name = vkey.substring(summarylistname.length());
						summarylist.put(name, value);
					}
					else{
						out.put(vkey,value);
					}
				}
				else{
					String vkey=fieldname;
					if(vkey.startsWith(summarylistname)){
						String name = vkey.substring(summarylistname.length());
						summarylist.put(name, "");
					}
					else{
						out.put(vkey,"");						
					}
				}
			}
		}
		if(summarylist.length()>0){
			out.put("summarylist", summarylist);
		}
		return out;
	}

	public JSONObject simpleRetrieveJSON(CSPRequestCredentials creds,CSPRequestCache cache,String filePath) throws ExistException,
	UnimplementedException, UnderlyingStorageException {
		try {
			JSONObject out=new JSONObject();
			if(r.isMultipart()){
				ReturnedMultipartDocument doc = conn.getMultipartXMLDocument(RequestMethod.GET,r.getServicesURL()+"/"+filePath,null,creds,cache);
				if((doc.getStatus()<200 || doc.getStatus()>=300))
					throw new ExistException("Does not exist "+filePath);
				for(String section : r.getServicesRecordPaths()) {
					String path=r.getServicesRecordPath(section);
					String[] parts=path.split(":",2);
					convertToJson(out,doc.getDocument(parts[0]));
				}
			}else{
				ReturnedDocument doc = conn.getXMLDocument(RequestMethod.GET, r.getServicesURL()+"/"+filePath,null, creds, cache);
				if((doc.getStatus()<200 || doc.getStatus()>=300))
					throw new ExistException("Does not exist "+filePath);
				for(String section : r.getServicesRecordPaths()) {
					String path=r.getServicesRecordPath(section);
					String[] parts=path.split(":",2);
					convertToJson(out,doc.getDocument());
				}
			}
			return out;
		} catch (ConnectionException e) {
			throw new UnderlyingStorageException("Service layer exception",e);
		} catch (JSONException e) {
			throw new UnderlyingStorageException("Service layer exception",e);
		}
	}

	public void updateJSON(ContextualisedStorage root,CSPRequestCredentials creds,CSPRequestCache cache,String filePath, JSONObject jsonObject)
	throws ExistException, UnimplementedException, UnderlyingStorageException {
		try {
			Map<String,Document> parts=new HashMap<String,Document>();
			Document doc = null;
			for(String section : r.getServicesRecordPaths()) {
				String path=r.getServicesRecordPath(section);
				String[] record_path=path.split(":",2);
				doc=XmlJsonConversion.convertToXml(r,jsonObject,section);
				parts.put(record_path[0],doc);
			}
			int status = 0;
			if(r.isMultipart()){
				ReturnedMultipartDocument docm = conn.getMultipartXMLDocument(RequestMethod.PUT,r.getServicesURL()+"/"+filePath,parts,creds,cache);
				status = docm.getStatus();
			}
			else{ 
				ReturnedDocument docm = conn.getXMLDocument(RequestMethod.PUT, r.getServicesURL()+"/"+filePath, doc, creds, cache);
				status = docm.getStatus();
			}
			
			if(status==404)
				throw new ExistException("Not found: "+r.getServicesURL()+"/"+filePath);
			if(status>299 || status<200)
				throw new UnderlyingStorageException("Bad response "+status);
		} catch (ConnectionException e) {
			throw new UnderlyingStorageException("Service layer exception",e);
		} catch (JSONException e) {
			throw new UnimplementedException("JSONException",e);

		}
	}
}
