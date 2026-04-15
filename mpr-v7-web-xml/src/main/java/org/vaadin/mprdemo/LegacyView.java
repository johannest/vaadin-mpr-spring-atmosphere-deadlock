package org.vaadin.mprdemo;

import com.vaadin.data.util.HierarchicalContainer;
import com.vaadin.ui.*;
import org.vaadin.treegrid.TreeGrid;

public class LegacyView extends VerticalLayout {

	private final Label label;

	public LegacyView(String foo) {
		label = new Label("Here we are in V7! "+foo);
		addComponent(label);
		addComponent(new Button("Show notification", e-> {
			Notification.show("V7 notification");
		}));

		Table table = new Table();
		

		TreeGrid treeGrid = new TreeGrid();
		HierarchicalContainer container = new HierarchicalContainer();
		container.addContainerProperty("bar", String.class, null);
		treeGrid.setContainerDataSource(container);
		treeGrid.addRow("bar");
		treeGrid.addRow("baz");
		treeGrid.setCellDescriptionGenerator(cell -> "");
		addComponent(treeGrid);
	}

	@Override
	public void attach() {
		System.out.println("** LegacyView.attach");
		super.attach();
	}

	@Override
	public void detach() {
		System.out.println("** LegacyView.detach");
		super.detach();
	}

	public void setValue(int counter) {
		label.setValue("Backgroud update (V7) "+counter);
    }
}
