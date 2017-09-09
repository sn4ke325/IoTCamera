package it.unibo.studio.iot.scc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.opencv.core.Point;
import org.opencv.core.Rect;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import it.unibo.studio.iot.scc.messages.Count;
import it.unibo.studio.iot.scc.messages.UpdateTracking;

public class TrackerActor extends AbstractActor {

	private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);
	private ActorRef counterActor;
	private Rect in_zone, out_zone;
	private double crossing_coord_in;// crossed[0]
	private double crossing_coord_out;// crossed[1]
	private boolean vertical, flipped;
	private int counter;
	private double max_distance_radius;
	// private int best_candidate;
	// private double best_candidate_distance;
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
	private Map<Integer, boolean[]> crossed;

	// quantization of hue and value
	private int q_hue, q_value;

	public TrackerActor(double i, double o, boolean v, boolean f, ActorRef actor) {
		this.crossing_coord_in = i;
		this.crossing_coord_out = o;
		this.vertical = v;
		this.flipped = f;
		this.counterActor = actor;
	}

	public void preStart() {
		this.counter = 0;
		this.pos_history = new HashMap<Integer, List<Point>>();
		this.alive_blobs = new HashMap<Integer, Blob>();
		this.updated_blobs = new HashMap<Integer, Boolean>();
		this.merged_blobs = new HashMap<Integer, List<Integer>>();
		this.crossed = new HashMap<Integer, boolean[]>();
		this.max_distance_radius = 25; // radius in which to find nearest
										// neighbors when matching blobs

		// quantization of hue and value
		// these values should be decided by the user depending on the camera
		this.q_hue = 5;
		this.q_value = 16;
	}

	public static Props props(double i, double o, boolean v, boolean f, ActorRef a) {
		return Props.create(TrackerActor.class, i, o, v, f, a);
	}

	@Override
	public Receive createReceive() {
		return receiveBuilder().match(UpdateTracking.class, r -> {

			updated_blobs.forEach((id, updated) -> {
				updated_blobs.replace(id, false);
			});

			if (alive_blobs.isEmpty()) {
				for (Blob b : r.getBlobs()) {
					// if the blob is in the tracking area add it to the alive
					// list
					// find which baseline it did cross using distance
					if (this.inTrackingArea(b.getCentroid())) {
						// blob list is empty so we fill it with the blobs that
						// crossed one of the baselines

						// generate id for this blob
						b.setID(generateID());
						// create the list for position history
						pos_history.put(b.id(), new ArrayList<Point>());
						// add first position
						pos_history.get(b.id()).add(b.getCentroid());
						// add the blob to the alive(tracked) list
						alive_blobs.put(b.id(), b);
						// add an entry for the crossed map
						this.crossed.put(b.id(), new boolean[2]);
						// find closer baseline
						// and set true to index 0 if IN is closer than OUT,
						// true on index 1 on the opposite
						this.crossed.get(b.id())[this.closestBaseline(b.getCentroid())] = true;

						// updated_blobs.put(b.id(), true);
					}

				}
			} else {// there are blobs in the tracked zone already being tracked
				alive_blobs.forEach((id, blob) -> {
					// controllo se esistono blob nel nuovo frame con centroide
					// vicino
					int best_candidate = -1;
					double best_candidate_distance = 100000;

					// find the closest blob in the previous frame within a
					// certain radius
					// from b
					for (int i = 0; i < r.getBlobs().size(); i++) {
						double CB = distance(r.getBlobs().get(i).getCentroid(), blob.getCentroid());
						if (CB <= max_distance_radius && CB < best_candidate_distance) {
							best_candidate = i;
							best_candidate_distance = CB;
						}

					}

					// if no candidate was found check using color vectors
					if (best_candidate == -1) {
						// make a list of possible candidates (for when people
						// wear the same colors)
						List<Integer> candidates = new ArrayList<Integer>();
						for (int i = 0; i < r.getBlobs().size(); i++) {
							if (Arrays.equals(blob.getCV(), r.getBlobs().get(i).getCV()))
								candidates.add(i);
						}

						// if something is found
						if (candidates.size() > 0) {
							// find the closest one
							for (int i = 0; i < r.getBlobs().size(); i++) {
								double CB = distance(r.getBlobs().get(candidates.get(i)).getCentroid(),
										blob.getCentroid());
								if (CB < best_candidate_distance) {
									best_candidate = i;
									best_candidate_distance = CB;
								}

							}
							// update the alive blob and remove from the new
							// blob list

						}

					}

					if (best_candidate != -1) {
						Blob candidate = r.getBlobs().remove(best_candidate);
						blob.update(candidate);
						pos_history.get(blob.id()).add(candidate.getCentroid());
					}

				});

				/*
				 * for (Blob b : r.getBlobs()) { // match the blob with one in
				 * the previous frame in order to // track it track(b); }
				 */
			}

			// check if blobs have crossed the line
			alive_blobs.forEach((id, b) -> {
				if (vertical) {// blobs cross horizontally
					int history_size = this.pos_history.get(id).size();
					if (history_size > 1) {
						double last_pos = this.pos_history.get(id).get(history_size - 1).x;
						double previous_pos = this.pos_history.get(id).get(history_size - 2).x;
						double origin = this.pos_history.get(id).get(0).x;
						if ((previous_pos > this.crossing_coord && last_pos <= this.crossing_coord)
								&& origin > this.crossing_coord
								|| (previous_pos < this.crossing_coord && last_pos >= this.crossing_coord
										&& origin < this.crossing_coord)) {
							// blob has crossed the line and needs to be counted
							// when leaves the are

							alive_blobs.get(id).setEvaluate(true);

						} else if ((previous_pos > this.crossing_coord && last_pos <= this.crossing_coord)
								&& origin < this.crossing_coord
								|| (previous_pos < this.crossing_coord && last_pos >= this.crossing_coord
										&& origin > this.crossing_coord)) {
							// blob is going back to where it came from
							alive_blobs.get(id).setEvaluate(false);
						}
					}

				} else {// blobs cross vertically
					int history_size = this.pos_history.get(id).size();
					if (history_size > 1) {
						double last_pos = this.pos_history.get(id).get(history_size - 1).y;
						double previous_pos = this.pos_history.get(id).get(history_size - 2).y;
						double origin = this.pos_history.get(id).get(0).y;
						if ((previous_pos > this.crossing_coord && last_pos <= this.crossing_coord)
								&& origin > this.crossing_coord
								|| (previous_pos < this.crossing_coord && last_pos >= this.crossing_coord
										&& origin < this.crossing_coord)) {
							// blob has crossed the line and needs to be counted
							// when leaves the are

							alive_blobs.get(id).setEvaluate(true);

						} else if ((previous_pos > this.crossing_coord && last_pos <= this.crossing_coord)
								&& origin < this.crossing_coord
								|| (previous_pos < this.crossing_coord && last_pos >= this.crossing_coord
										&& origin > this.crossing_coord)) {
							// blob is going back to where it came from
							alive_blobs.get(id).setEvaluate(false);
						}
					}
				}
			});

			// check if some blobs haven't been updated and clean them based on
			// their position in the scene
			Integer[] keys = new Integer[updated_blobs.keySet().size()];
			updated_blobs.keySet().toArray(keys);
			for (Integer id : keys) {
				if (!updated_blobs.get(id)) {
					int count = 0;
					Blob b = alive_blobs.remove(id);
					b.kill();
					updated_blobs.remove(id);
					pos_history.remove(id);
					if (b.evaluate()) {
						if (vertical) {
							if (!flipped) {
								if (b.getCentroid().x < this.crossing_coord)
									count = b.weight(); // blob is entering
								else
									count = -b.weight(); // blob is leaving
							} else {
								if (b.getCentroid().x < this.crossing_coord)
									count = -b.weight(); // blob is leaving
								else
									count = b.weight(); // blob is entering
							}
						} else {
							if (!flipped) {
								if (b.getCentroid().y < this.crossing_coord)
									count = b.weight(); // blob is entering
								else
									count = -b.weight(); // blob is leaving
							} else {
								if (b.getCentroid().y < this.crossing_coord)
									count = -b.weight(); // blob is leaving
								else
									count = b.weight(); // blob is entering
							}
						}

					}

					if (count != 0) {
						log.info("Blob with ID " + Integer.toString(b.id()) + " has left the scene. Counting "
								+ Integer.toString(count));
						counterActor.tell(new Count(count), this.self());
					}

				}
			}

			// getSender().tell(new UpdateTracking(new
			// ArrayList<Blob>(alive_blobs.values())), this.getSelf());

		}).build();
	}

	private int closestBaseline(Point p) {
		// returns 0 if IN is closest, else 1
		if (vertical) {
			if (distance(p, new Point(crossing_coord_in, p.y)) < distance(p, new Point(crossing_coord_out, p.y)))
				return 0;
			return 1;

		} else {
			if (distance(p, new Point(crossing_coord_in, p.x)) < distance(p, new Point(crossing_coord_out, p.x)))
				return 0;
			return 1;

		}
	}

	private boolean inTrackingArea(Point p) {
		if (vertical)
			return p.x >= this.crossing_coord_in && p.x <= this.crossing_coord_out;

		return p.y >= this.crossing_coord_in && p.y <= this.crossing_coord_out;

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
		if (best_candidate == -1) {

			// a new blob has entered the scene
			// add it to the list of tracked blobs
			b.setID(generateID());
			pos_history.put(b.id(), new ArrayList<Point>());
			pos_history.get(b.id()).add(b.getCentroid());
			alive_blobs.put(b.id(), b);
			updated_blobs.put(b.id(), true);

		} else

		{// a best candidate has been found, time to replace the previous
			// frame's blob with the new one and update the tracking
			b.setID(best_candidate);
			alive_blobs.get(best_candidate).update(b);
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
