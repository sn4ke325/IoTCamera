package it.unibo.studio.iot.scc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opencv.core.Point;
import org.opencv.core.Rect;

import akka.actor.AbstractActor;
import akka.actor.Props;
import it.unibo.studio.iot.scc.messages.UpdateTracking;

public class TrackerActor extends AbstractActor {

	private Rect in_zone, out_zone;
	private int counter;
	private double max_distance_radius;
	private int best_candidate;
	private double best_candidate_distance;
	private Vector closest_speed_delta;
	private Map<Integer, List<Point>> pos_history; // tracks the history of
													// positions for each ID
	private Map<Integer, Blob> alive_blobs; // map that holds the blobs in the
											// scene with their IDs

	public TrackerActor(Rect in, Rect out) {
		this.in_zone = in;
		this.out_zone = out;
		this.counter = 0;

	}

	public void preStart() {
		this.pos_history = new HashMap<Integer, List<Point>>();
		this.alive_blobs = new HashMap<Integer, Blob>();
		this.max_distance_radius = 3; // radius in which to find nearest
										// neighbors when matching blobs
	}

	public static Props props(Rect in, Rect out) {
		return Props.create(TrackerActor.class, in, out);
	}

	@Override
	public Receive createReceive() {
		return receiveBuilder().match(UpdateTracking.class, r -> {
			
			if (alive_blobs.isEmpty()) {
				for (Blob b : r.getBlobs()) {
					b.setID(generateID());
					alive_blobs.put(b.id(), b);
				}
			} else {
				for (Blob b : r.getBlobs()) {
					// match the blob with one in the previous frame in order to
					// track it
					track(b);
				}
			}
		}).build();
	}

	private int generateID() {
		while (alive_blobs.containsKey(counter)) {
			counter++;
			if (counter > 20)
				counter = 0;
		}
		return counter;
	}

	private void track(Blob b) {
		best_candidate = -1;
		best_candidate_distance = 100000;
		
		//find the closest blob in the previous frame within a certain radius from b
		alive_blobs.forEach((id, c) -> {
			double CB = distance(b.getCentroid(), c.getCentroid());
			if (CB <= max_distance_radius && CB < best_candidate_distance) {
				best_candidate = id;
				best_candidate_distance = CB;
			}

		});
	}

	private double distance(Point a, Point b) {
		return Math.sqrt(Math.pow(a.x - b.x, 2) + Math.pow(a.y - b.y, 2));
	}

}

class Vector {
	private double d, v, x, y;

	public Vector(double x, double y) {
		this.x = x;
		this.y = y;
		this.d = Math.atan(y / x);
		this.v = x / Math.cos(d);
	}

	public double direction() {
		return d;
	}

	public double value() {
		return v;
	}

	public double x() {
		return x;
	}

	public double y() {
		return y;
	}

	public boolean equals(Vector a) {
		if (a.x() == this.x && a.y() == this.y)
			return true;
		return false;
	}
}
