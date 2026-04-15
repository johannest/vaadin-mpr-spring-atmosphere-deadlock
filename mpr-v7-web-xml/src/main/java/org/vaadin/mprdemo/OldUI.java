package org.vaadin.mprdemo;

import com.vaadin.mpr.MprUI;
import com.vaadin.server.VaadinRequest;

public class OldUI extends MprUI {

	String hello = "Hello";
	
	public String getHello() {
		return hello;
	}

	@Override
	protected void init(VaadinRequest request) {
		System.out.println("************* init");
		super.init(request);
		System.out.println("************* inited");
	}
}
