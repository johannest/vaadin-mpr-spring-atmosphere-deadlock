package org.vaadin.mprdemo;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Hr;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.communication.PushMode;
import com.vaadin.flow.shared.ui.Transport;
import com.vaadin.mpr.LegacyWrapper;
import com.vaadin.mpr.core.LegacyUI;
import com.vaadin.mpr.core.MprTheme;
import com.vaadin.mpr.core.MprWidgetset;

/**
 * Main Vaadin Flow view with embedded legacy Vaadin 7 component via MPR.
 * <p>
 * Push is enabled (AUTOMATIC + WEBSOCKET_XHR) to ensure the
 * {@code AtmospherePushConnection} is active — a prerequisite for the
 * deadlock.
 * <p>
 * The <b>Logout</b> link navigates to Spring Security's {@code /logout}
 * endpoint which calls {@code HttpSession.invalidate()} — the Thread B
 * trigger.
 */
@Push(value = PushMode.AUTOMATIC, transport = Transport.WEBSOCKET_XHR)
@Route("")
@MprTheme("mytheme")
@CssImport("custom.css")
@LegacyUI(OldUI.class)
@MprWidgetset("org.vaadin.mprdemo.MyWidgetSet")
public class MyUI extends VerticalLayout {
	private final LegacyView legacyComponent;
	private final VerticalLayout pushLayout = new VerticalLayout();

	@SuppressWarnings("deprecation")
	public MyUI() {
		// --- Header with test and logout links ---
		Anchor deadlockTestLink = new Anchor("deadlock-test", "Deadlock Test");
		deadlockTestLink.getStyle()
				.set("color", "blue")
				.set("font-weight", "bold");
		deadlockTestLink.setTarget("_blank");   // opens in new tab

		Anchor deadlockNaturalLink = new Anchor(
				"deadlock-test?mode=natural", "Natural Test");
		deadlockNaturalLink.getStyle()
				.set("color", "blue")
				.set("font-size", "small");
		deadlockNaturalLink.setTarget("_blank");

		Anchor logoutLink = new Anchor("logout", "Logout");
		logoutLink.getStyle()
				.set("margin-left", "auto")
				.set("color", "red")
				.set("font-weight", "bold");

		Span info = new Span("Push: AUTOMATIC / WEBSOCKET_XHR — "
				+ "click Logout to trigger HttpSession.invalidate()");
		info.getStyle().set("font-size", "small").set("color", "gray");

		HorizontalLayout header = new HorizontalLayout(
				info, deadlockTestLink, deadlockNaturalLink, logoutLink);
		header.setWidthFull();
		header.setDefaultVerticalComponentAlignment(Alignment.CENTER);
		add(header);
		add(new Hr());

		// --- Flow content ---
		H3 flowLabel = new H3("Flow H3");
		add(pushLayout);
		add(flowLabel);

		// --- Legacy V7 content via MPR ---
		legacyComponent = new LegacyView("adsd");
		add(new LegacyWrapper(legacyComponent));
	}

	@Override
	protected void onDetach(DetachEvent detachEvent) {
		super.onDetach(detachEvent);
		UI ui = detachEvent.getUI();
		ui.access(() -> {
			// Let Vaadin handle cleanup
			ui.getSession().close();
		});
	}

	@Override
	public void onAttach(AttachEvent event) {
		FeederThread thread = new FeederThread(event.getUI(), this, legacyComponent);
		thread.start();
	}

	private static class FeederThread extends Thread {
		private final UI ui;
		private final VerticalLayout view;
		private LegacyView legacyView;
		private int count = 0;

		public FeederThread(UI ui, VerticalLayout view, LegacyView legacyView) {
			this.ui = ui;
			this.view = view;
			this.legacyView = legacyView;
		}

		@Override
		public void run() {
			try {
				while (count < 10) {
					Thread.sleep(2000);
					String message = "This is update " + count++;
					ui.access(() -> view.add(new Span(message)));
					ui.access(() -> legacyView.setValue(count));
					System.out.println(message);
				}
				ui.access(() -> {
					view.add(new Span("Done updating"));
				});
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
}
