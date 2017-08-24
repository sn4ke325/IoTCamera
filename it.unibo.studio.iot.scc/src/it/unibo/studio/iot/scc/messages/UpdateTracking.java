package it.unibo.studio.iot.scc.messages;

import java.util.ArrayList;
import java.util.List;

import it.unibo.studio.iot.scc.Blob;

public class UpdateTracking {
	private List<Blob> blobs;

	public UpdateTracking(List<Blob> l) {
		this.blobs = new ArrayList<Blob>();
		blobs.addAll(l);

	}

	public List<Blob> getBlobs() {
		return blobs;
	}

}
