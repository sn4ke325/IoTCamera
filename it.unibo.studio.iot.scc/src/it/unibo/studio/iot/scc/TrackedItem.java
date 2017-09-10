package it.unibo.studio.iot.scc;

import java.util.ArrayList;
import java.util.List;

import org.opencv.core.Point;

public class TrackedItem {
	private Blob blob;
	private List<Point> position_history;
	private int entering_baseline; // 0 for IN baseline, 1 otherwise
	private int ID;

	public TrackedItem(Blob b, int id, int bl) {
		this.blob = b;
		this.ID = id;
		this.entering_baseline = bl;
		this.position_history = new ArrayList<Point>();
		this.position_history.add(blob.getCentroid());
	}

	public Blob getBlob() {
		return this.blob;
	}

	public void updateBlob(Blob b) {
		this.blob = b;
		this.position_history.add(blob.getCentroid());
	}

	public List<Point> getPosHistory() {
		return new ArrayList<Point>(this.position_history);
	}

	public int getID() {
		return this.ID;
	}

	public Point lastPos() {
		return position_history.get(position_history.size() - 1);
	}

	public int baseline() {
		return entering_baseline;
	}

}
