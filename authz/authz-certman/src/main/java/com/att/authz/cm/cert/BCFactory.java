/*******************************************************************************
 * Copyright (c) 2016 AT&T Intellectual Property. All rights reserved.
 *******************************************************************************/
package com.att.authz.cm.cert;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.util.List;

import org.bouncycastle.asn1.ASN1Object;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;

import com.att.authz.cm.ca.CA;
import com.att.authz.cm.validation.Validator;
import com.att.cadi.Symm;
import com.att.cadi.cm.CertException;
import com.att.cadi.cm.Factory;
import com.att.inno.env.Env;
import com.att.inno.env.TimeTaken;
import com.att.inno.env.Trans;


/**
 * Additional Factory mechanisms for CSRs, and BouncyCastle.  The main Factory
 * utilizes only Java abstractions, and is useful in Client code.
 * 

 *
 */
public class BCFactory extends Factory {
	private static final JcaContentSignerBuilder jcsb;


	static {
		// Bouncy
		jcsb = new JcaContentSignerBuilder(Factory.SIG_ALGO);
	}
	
	public static ContentSigner contentSigner(PrivateKey pk) throws OperatorCreationException {
		return jcsb.build(pk);
	}
	
	public static String toString(Trans trans, PKCS10CertificationRequest csr) throws IOException, CertException {
		TimeTaken tt = trans.start("CSR to String", Env.SUB);
		try {
			if(csr==null) {
				throw new CertException("x509 Certificate Request not built");
			}
			return textBuilder("CERTIFICATE REQUEST",csr.getEncoded());
		}finally {
			tt.done();
		}
	}

	public static PKCS10CertificationRequest toCSR(Trans trans, File file) throws IOException {
		TimeTaken tt = trans.start("Reconstitute CSR", Env.SUB);
		try {
			FileReader fr = new FileReader(file);
			return new PKCS10CertificationRequest(decode(strip(fr)));
		} finally {
			tt.done();
		}
	}

	public static byte[] sign(Trans trans, ASN1Object toSign, PrivateKey pk) throws IOException, InvalidKeyException, SignatureException, NoSuchAlgorithmException {
		TimeTaken tt = trans.start("Encode Security Object", Env.SUB);
		try {
			return sign(trans,toSign.getEncoded(),pk);
		} finally {
			tt.done();
		}
	}

	public static CSRMeta createCSRMeta(CA ca,final String args[]) throws IllegalArgumentException, IllegalAccessException, CertException {
		CSRMeta csr = new CSRMeta();
		ca.stdFields().set(csr);
		//TODO should we checkDigest?
//		digest = ca.messageDigest();

		Field[] fld = CSRMeta.class.getDeclaredFields();
		for(int i=0;i+1<args.length;++i) {
			if(args[i].charAt(0)=='-') {
				for(int j=0;j<fld.length;++j) {
					if(fld[j].getType().equals(String.class) && args[i].substring(1).equals(fld[j].getName())) {
						fld[j].set(csr,args[++i]);
						break;
					}
				}
			}
		}
		String errs = validate(csr);
		if(errs!=null) {
			throw new CertException(errs);
		}
		return csr;
	}
	
	
	public static CSRMeta createCSRMeta(CA ca, String mechid, String sponsorEmail, List<String> fqdns) throws CertException {
		CSRMeta csr = new CSRMeta();
		boolean first = true;
		// Set CN (and SAN)
		for(String fqdn : fqdns) {
			if(first) {
				first = false;
				csr.cn(fqdn);
			} else {
				csr.san(fqdn);
			}
		}
		
		csr.challenge(new String(Symm.randomGen(24)));
		ca.stdFields().set(csr);
		csr.mechID(mechid);
		csr.email(sponsorEmail);
		String errs = validate(csr);
		if(errs!=null) {
			throw new CertException(errs);
		}
		return csr;
	}

	private static String validate(CSRMeta csr) {
		Validator v = new Validator();
		if(v.nullOrBlank("cn", csr.cn())
			.nullOrBlank("mechID", csr.mechID())
			.nullOrBlank("email", csr.email())
			.nullOrBlank("o",csr.o())
			.nullOrBlank("l",csr.l())
			.nullOrBlank("st",csr.st())
			.nullOrBlank("c",csr.c())
			.err()) {
			return v.errs();
		} else {
			return null;
		}
	}
	

}
