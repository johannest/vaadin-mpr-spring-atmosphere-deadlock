package org.vaadin.mprdemo;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;

public class ApplicationServiceInitListener implements VaadinServiceInitListener {

    @Override
    public void serviceInit(ServiceInitEvent serviceInitEvent) {
        serviceInitEvent.getSource().addSessionDestroyListener(e->{
            System.out.println("Session "+e.getSession().getSession().getId()+" destroyed");
        });
        serviceInitEvent.getSource().addSessionInitListener(e->{
            System.out.println("Session "+e.getSession().getSession().getId()+" inited");
            e.getSession().getSession().setMaxInactiveInterval(300);
            System.out.println("will close idle session: "+e.getSession().getConfiguration().isCloseIdleSessions());
        });
    }
}
