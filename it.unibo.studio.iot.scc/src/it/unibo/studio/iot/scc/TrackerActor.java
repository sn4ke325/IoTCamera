package it.unibo.studio.iot.scc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opencv.core.Point;
import org.opencv.core.Rect;

import akka.actor.AbstractActor;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import it.unibo.studio.iot.scc.messages.UpdateTracking;

public class TrackerActor extends AbstractActor {

	private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

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
	private Map<Integer, Boolean> updated_blobs;// keeps track of the blobs that
												// got an update due to tracking
	private Map<Integer, List<Integer>> merged_blobs; // maps smaller blobs that
														// got merged into
														// bigger ones to track
														// them if they
														// eventually split

	public TrackerActor(Rect in, Rect out) {
		this.in_zone = in;
		this.out_zone = out;

	}

	public void preStart() {
		this.counter = 0;
		this.pos_history = new HashMap<Integer, List<Point>>();
		this.alive_blobs = new HashMap<Integer, Blob>();
		this.updated_blobs = new HashMap<Integer, Boolean>();
		this.merged_blobs = new HashMap<Integer, List<Integer>>();
		this.max_distance_radius = 10; // radius in which to find nearest
										// neighbors when matching blobs
	}

	public static Props props(Rect in, Rect out) {
		return Props.create(TrackerActor.class, in, out);
	}

	@Override
	public Receive createReceive() {
		return receiveBuilder().match(UpdateTracking.class, r -> {

			updated_blobs.forEach((id, updated) -> {
				updated_blobs.replace(id, false);
			});

			if (alive_blobs.isEmpty()) {
				for (Blob b : r.getBlobs()) {
					b.setID(generateID());
					pos_history.put(b.id(), new ArrayList<Point>());
					pos_history.get(b.id()).add(b.getCentroid());
					alive_blobs.put(b.id(), b);
					updated_blobs.put(b.id(), true);
				}
			} else {
				for (Blob b : r.getBlobs()) {
					// match the blob with one in the previous frame in order to
					// track it
					track(b);
				}
			}

			// check if some blobs haven't been updated and clean them based on
			// their position in the scene

			for (Integer id : updated_blobs.keySet()) {
				if (!updated_blobs.get(id)) {
					int count = 0;
					Blob b = alive_blobs.remove(id);
					b.kill();
					updated_blobs.remove(id);
					if (in_zone.contains(b.getCentroid())) {
						// count IN and kill blob and stop tracking
						count = b.weight();
						pos_history.remove(id);
						log.info(Integer.toString(count));

					} else if (out_zone.contains(b.getCentroid())) {
						count = -b.weight();
						pos_history.remove(id);
						log.info(Integer.toString(count));
					} else {
						// the blob disappeared in the mid zone, it might have
						// merged with other blobs
					}

					if (count != 0) {
						// send message to counterActor to count
						// TODO
					}

				}
			}

			// getSender().tell(new UpdateTracking(new
			// ArrayList<Blob>(alive_blobs.values())), this.getSelf());

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

		// find the closest blob in the previous frame within a certain radius
		// from b
		alive_blobs.forEach((id, c) -> {
			double CB = distance(b.getCentroid(), c.getCentroid());
			if (CB <= max_distance_radius && CB < best_candidate_distance) {
				best_candidate = id;
				best_candidate_distance = CB;
			}

		});
		// if best candidate is not found then a new blob must have entered the
		// scene and needs to be tracked
		if (best_candidate == -1)
			if (this.in_zone.contains(b.getCentroid()) || this.out_zone.contains(b.getCentroid())) {

				// a new blob has entered the scene
				// add it to the list of tracked blobs
				b.setID(generateID());
				pos_history.put(b.id(), new ArrayList<Point>());
				pos_history.get(b.id()).add(b.getCentroid());
				alive_blobs.put(b.id(), b);
				updated_blobs.put(b.id(), true);

			} else {
				// the new blob was detected in the middle zone, so it must be
				// originating from a blob SPLIT scenario
			}
		else {// a best candidate has been found, time to replace the previous
				// frame's blob with the new one and update the tracking
			b.setID(best_candidate);
			alive_blobs.put(best_candidate, b);
			pos_history.get(best_candidate).add(b.getCentroid());
			updated_blobs.put(best_candidate, true);

		}
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
