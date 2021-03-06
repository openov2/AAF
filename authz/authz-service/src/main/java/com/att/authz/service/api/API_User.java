/*******************************************************************************
 * Copyright (c) 2016 AT&T Intellectual Property. All rights reserved.
 *******************************************************************************/
package com.att.authz.service.api;

import static com.att.authz.layer.Result.OK;
import static com.att.cssa.rserv.HttpMethods.GET;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.att.aft.dme2.internal.jetty.http.HttpStatus;
import com.att.authz.env.AuthzTrans;
import com.att.authz.facade.AuthzFacade;
import com.att.authz.layer.Result;
import com.att.authz.service.AuthAPI;
import com.att.authz.service.Code;
import com.att.authz.service.mapper.Mapper.API;

/**
 * User Role APIs
 *
 */
public class API_User {
	/**
	 * Normal Init level APIs
	 * 
	 * @param authzAPI
	 * @param facade
	 * @throws Exception
	 */
	public static void init(final AuthAPI authzAPI, AuthzFacade facade) throws Exception {
		/**
		 * get all Users who have Permission X
		 */
		authzAPI.route(GET,"/authz/users/perm/:type/:instance/:action",API.USERS,new Code(facade,"Get Users By Permission", true) {
			@Override
			public void handle(AuthzTrans trans, HttpServletRequest req, HttpServletResponse resp) throws Exception {
//				trans.checkpoint(pathParam(req,"type") + " " 
//						+ pathParam(req,"instance") + " " 
//						+ pathParam(req,"action"));
//
				Result<Void> r = context.getUsersByPermission(trans, resp,
						pathParam(req, ":type"),
						pathParam(req, ":instance"),
						pathParam(req, ":action"));
				switch(r.status) {
					case OK:
						resp.setStatus(HttpStatus.OK_200); 
						break;
					default:
						context.error(trans,resp,r);
				}
			}
		});


		/**
		 * get all Users who have Role X
		 */
		authzAPI.route(GET,"/authz/users/role/:role",API.USERS,new Code(facade,"Get Users By Role", true) {
			@Override
			public void handle(AuthzTrans trans, HttpServletRequest req, HttpServletResponse resp) throws Exception {
				Result<Void> r = context.getUsersByRole(trans, resp, pathParam(req, ":role"));
				switch(r.status) {
					case OK:
						resp.setStatus(HttpStatus.OK_200); 
						break;
					default:
						context.error(trans,resp,r);
				}
			}
		});
		
		/**
		 * Get User Role if exists
		 * @deprecated
		 */
		authzAPI.route(GET,"/authz/userRole/:user/:role",API.USERS,new Code(facade,"Get if User is In Role", true) {
			@Override
			public void handle(AuthzTrans trans, HttpServletRequest req, HttpServletResponse resp) throws Exception {
				Result<Void> r = context.getUserInRole(trans, resp, pathParam(req,":user"),pathParam(req,":role"));
				switch(r.status) {
					case OK:
						resp.setStatus(HttpStatus.OK_200); 
						break;
					default:
						context.error(trans,resp,r);
				}
			}
		});

		/**
		 * Get User Role if exists
		 */
		authzAPI.route(GET,"/authz/users/:user/:role",API.USERS,new Code(facade,"Get if User is In Role", true) {
			@Override
			public void handle(AuthzTrans trans, HttpServletRequest req, HttpServletResponse resp) throws Exception {
				Result<Void> r = context.getUserInRole(trans, resp, pathParam(req,":user"),pathParam(req,":role"));
				switch(r.status) {
					case OK:
						resp.setStatus(HttpStatus.OK_200); 
						break;
					default:
						context.error(trans,resp,r);
				}
			}
		});
		


	}
		
}
