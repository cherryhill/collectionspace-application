/* Copyright 2010 University of Cambridge
 * Licensed under the Educational Community License (ECL), Version 2.0. You may not use this file except in 
 * compliance with this License.
 *
 * You may obtain a copy of the ECL 2.0 License at https://source.collectionspace.org/collection-space/LICENSE.txt
 */
package org.collectionspace.chain.csp.webui.record;

import org.apache.commons.lang.StringUtils;
import org.collectionspace.chain.csp.config.ConfigException;
import org.collectionspace.chain.csp.schema.Record;
import org.collectionspace.chain.csp.schema.Spec;
import org.collectionspace.chain.csp.webui.main.Request;
import org.collectionspace.chain.csp.webui.main.WebMethod;
import org.collectionspace.chain.csp.webui.main.WebUI;
import org.collectionspace.chain.csp.webui.misc.Generic;
import org.collectionspace.csp.api.persistence.ExistException;
import org.collectionspace.csp.api.persistence.Storage;
import org.collectionspace.csp.api.persistence.UnderlyingStorageException;
import org.collectionspace.csp.api.persistence.UnimplementedException;
import org.collectionspace.csp.api.ui.Operation;
import org.collectionspace.csp.api.ui.UIException;
import org.collectionspace.csp.api.ui.UIRequest;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RecordCreateUpdate implements WebMethod {
	private static final Logger log=LoggerFactory.getLogger(RecordCreateUpdate.class);
	private String url_base,base;
	private boolean create;
	private Spec spec;
	private RecordRead reader;
	private RecordSearchList searcher;
	
	public RecordCreateUpdate(Record r,boolean create) { 
		spec=r.getSpec();
		this.url_base=r.getWebURL();
		this.base=r.getID();
		this.create=create;
		this.reader=new RecordRead(r);
		this.reader.configure(spec);
		this.searcher = new RecordSearchList(r,false);
	}
		
	private void deleteAllRelations(Storage storage,String csid) throws JSONException, ExistException, UnimplementedException, UnderlyingStorageException {
		JSONObject r=new JSONObject();
		r.put("src",base+"/"+csid);	
		// XXX needs pagination support CSPACE-1819
		JSONObject data = storage.getPathsJSON("relations/main",r);
		String[] paths = (String[]) data.get("listItems");
		for(String relation : paths) {
			storage.deleteJSON("relations/main/"+relation);
		}
	}
	
	private void setRelations(Storage storage,String csid,JSONArray relations) throws JSONException, ExistException, UnimplementedException, UnderlyingStorageException {
		deleteAllRelations(storage,csid);
		for(int i=0;i<relations.length();i++) {
			// Extract data from miniobject
			JSONObject in=relations.getJSONObject(i);
			String dst_type=spec.getRecordByWebUrl(in.getString("recordtype")).getID();
			String dst_id=in.getString("csid");
			String type=in.getString("relationshiptype");
			// Create relation
			JSONObject r=new JSONObject();
			r.put("src",base+"/"+csid);
			r.put("dst",dst_type+"/"+dst_id);
			r.put("type",type);
			storage.autocreateJSON("relations/main",r);
		}
	}
	
	public String sendJSON(Storage storage,String path,JSONObject data) throws ExistException, UnimplementedException, UnderlyingStorageException, JSONException {
		JSONObject fields=data.optJSONObject("fields");
		JSONArray relations=data.optJSONArray("relations");
		if(path!=null) {
			// Update
			if(fields!=null)
				storage.updateJSON(base+"/"+path,fields);
		} else {
			// Create
			if(fields!=null)
				path=storage.autocreateJSON(base,fields);
		}
		if(relations!=null)
			setRelations(storage,path,relations);
		return path;
	}
			
	private JSONObject permJSON(String permValue) throws JSONException{
		JSONObject perm = new JSONObject();
		perm.put("name", permValue);
		return perm;
	}
	private JSONObject getPerm(Storage storage, String resourceName, String permlevel) throws JSONException, ExistException, UnimplementedException, UnderlyingStorageException, UIException{

		JSONObject permitem = new JSONObject();
		JSONArray actions =  new JSONArray();
		JSONObject permR = permJSON("READ");
		JSONObject permC = permJSON("CREATE");
		JSONObject permU = permJSON("UPDATE");
		JSONObject permD = permJSON("DELETE");
		JSONObject permL = permJSON("SEARCH");

		///cspace-services/authorization/permissions?res=acquisition&actGrp=CRUDL
		String queryString = "CRUDL";
		if(permlevel.equals("none")){
			queryString = "";
			actions = new JSONArray();
			return permitem;
		}
		if(permlevel.equals("read")){
			queryString = "RL";
			actions.put(permR);
			actions.put(permL);
		}
		if(permlevel.equals("write")){
			queryString = "CRUL";
			actions.put(permC);
			actions.put(permR);
			actions.put(permU);
			actions.put(permL);
		}
		if(permlevel.equals("delete")){
			queryString = "CRUDL";
			actions.put(permC);
			actions.put(permR);
			actions.put(permU);
			actions.put(permD);
			actions.put(permL);
		}

		JSONObject permrestrictions = new JSONObject();
		permrestrictions.put("keywords", Generic.ResourceNameServices(spec, resourceName));
		permrestrictions.put("queryTerm", "actGrp");
		permrestrictions.put("queryString", queryString);

		String permbase = spec.getRecordByWebUrl("permission").getID();
		JSONObject data = searcher.getJSON(storage,permrestrictions,"items",permbase);

		String permid = "";
		JSONArray items = data.getJSONArray("items");
		for(int i=0;i<items.length();i++){
			JSONObject item = items.getJSONObject(i);
			JSONObject permission = new JSONObject();
			String resourcename = item.getString("summary");
			String actionGroup = item.getString("number");
			//need to do a double check as the query is an inexact match
			if(resourcename.equals(Generic.ResourceNameServices(spec, resourceName)) && actionGroup.equals(queryString)){
				permid = item.getString("csid");
			}
		}
		if(permid.equals("")){

			//create the permission
			/**
			 * {
"effect": "PERMIT",
"resourceName": "testthing2",

"action":[{"name":"CREATE"},{"name":"READ"},{"name":"UPDATE"},{"name":"DELETE"},{"name":"SEARCH"}]
}
			 */
					
			JSONObject permission_add = new JSONObject();
			permission_add.put("effect", "PERMIT");
			permission_add.put("resourceName", Generic.ResourceNameServices(spec, resourceName));
			permission_add.put("actionGroup", queryString);
			permission_add.put("action", actions);

			permid=storage.autocreateJSON(spec.getRecordByWebUrl("permission").getID(),permission_add);
			
		}
		
		

		if(!permid.equals("")){
			permitem.put("resourceName", Generic.ResourceNameServices(spec, resourceName));
			permitem.put("permissionId", permid);
			permitem.put("actionGroup", queryString);
		}
		return permitem;

		
	}
	private void assignPermissions(Storage storage, String path, JSONObject data) throws JSONException, ExistException, UnimplementedException, UnderlyingStorageException, UIException{
		JSONObject fields=data.optJSONObject("fields");
		
		JSONArray permdata = new JSONArray();
		if(fields.has("permissions")){
			JSONArray permissions = fields.getJSONArray("permissions");
			for(int i=0;i<permissions.length();i++){
				JSONObject perm = permissions.getJSONObject(i);
				JSONObject permitem = getPerm(storage,perm.getString("resourceName"),perm.getString("permission"));
				if(permitem.has("permissionId")){
					permdata.put(permitem);
				}
			}
		}

		JSONObject roledata = new JSONObject();
		roledata.put("roleName", fields.getString("roleName"));

		String[] ids=path.split("/");
		roledata.put("roleId", ids[ids.length - 1]);
		
		

		JSONObject accountrole = new JSONObject();
		JSONObject arfields = new JSONObject();
		arfields.put("role", roledata);
		arfields.put("permission", permdata);
		accountrole.put("fields", arfields);
		
		if(fields!=null)
			path=storage.autocreateJSON(spec.getRecordByWebUrl("permrole").getID(),arfields);
	}
	
	private void store_set(Storage storage,UIRequest request,String path) throws UIException {
		try {
			JSONObject data=request.getJSONBody();

			if(this.base.equals("role")){
				JSONObject fields=data.optJSONObject("fields");
				if((fields.optString("roleName") == null || fields.optString("roleName").equals("")) && fields.optString("displayName") !=null){
					String test  = fields.optString("displayName");
					test = test.toUpperCase();
					test.replaceAll("\\W", "_");
					fields.put("roleName", "ROLE_"+test);
					data.put("fields", fields);
				}
			}
			
			if(create) {
				path=sendJSON(storage,null,data);
				data.put("csid",path);
				data.getJSONObject("fields").put("csid",path);
			} else
				path=sendJSON(storage,path,data);
			if(path==null)
				throw new UIException("Insufficient data for create (no fields?)");

			if(this.base.equals("role")){
				assignPermissions(storage,path,data);
			}
			
			data=reader.getJSON(storage,path);
			request.sendJSONResponse(data);
			request.setOperationPerformed(create?Operation.CREATE:Operation.UPDATE);
			if(create)
				request.setSecondaryRedirectPath(new String[]{url_base,path});
		} catch (JSONException x) {
			throw new UIException("Failed to parse json: "+x,x);
		} catch (ExistException x) {
			throw new UIException("Existence exception: "+x,x);
		} catch (UnimplementedException x) {
			throw new UIException("Unimplemented exception: "+x,x);
		} catch (UnderlyingStorageException x) {
			UIException uiexception =  new UIException(x.getMessage(),x.getStatus(),x.getUrl(),x);
			request.sendJSONResponse(uiexception.getJSON());
		}catch (Exception x) {
			throw new UIException(x);
		}
	
	}
	
	public void run(Object in, String[] tail) throws UIException {
		Request q=(Request)in;
		store_set(q.getStorage(),q.getUIRequest(),StringUtils.join(tail,"/"));
	}

	public void configure() throws ConfigException {}
	
	public void configure(WebUI ui,Spec spec) {}
}
