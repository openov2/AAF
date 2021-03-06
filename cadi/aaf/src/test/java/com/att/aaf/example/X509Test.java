/*******************************************************************************
 * Copyright (c) 2016 AT&T Intellectual Property. All rights reserved.
 *******************************************************************************/
package com.att.aaf.example;

import java.security.Principal;

import com.att.cadi.PropAccess;
import com.att.cadi.aaf.v2_0.AAFConHttp;
import com.att.cadi.aaf.v2_0.AAFLurPerm;
import com.att.cadi.client.Future;
import com.att.cadi.locator.DNSLocator;
import com.att.cadi.lur.LocalPermission;

public class X509Test {
	public static void main(String args[]) {
		// Link or reuse to your Logging mechanism
		
		PropAccess myAccess = new PropAccess();
		
		// 
		try {
			AAFConHttp con = new AAFConHttp(myAccess, 
					new DNSLocator(myAccess,"https","mithrilcsp.sbc.com","8100"));
			
			// AAFLur has pool of DME clients as needed, and Caches Client lookups
			AAFLurPerm aafLur = con.newLur();
			
			// Note: If you need both Authn and Authz construct the following:
//			AAFAuthn<?> aafAuthn = con.newAuthn(aafLur);
			
			// con.x509Alias("aaf.att"); // alias in keystore

			try {
				
				// Normally, you obtain Principal from Authentication System.
//				// For J2EE, you can ask the HttpServletRequest for getUserPrincipal()
//				// If you use CADI as Authenticator, it will get you these Principals from
//				// CSP or BasicAuth mechanisms.
//				String id = "cluster_admin@gridcore.att.com";
//
//				// If Validate succeeds, you will get a Null, otherwise, you will a String for the reason.
				Future<String> fs = 
						con.client("2.0").read("/authz/perms/com.att.aaf.ca","application/Perms+json");
				if(fs.get(3000)) {
					System.out.println(fs.value);
				} else {
					System.out.println("Error: "  + fs.code() + ':' + fs.body());
				}
				
				// Check on Perms with LUR
				if(aafLur.fish(new Principal() {
					@Override
					public String getName() {
						return "m12345@aaf.att.com";
					}
				}, new LocalPermission("com.att.aaf.ca|aaf|request"))) {
					System.out.println("Has Perm");
				} else {
					System.out.println("Does NOT Have Perm");
				}
			} finally {
				aafLur.destroy();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

	}
}
