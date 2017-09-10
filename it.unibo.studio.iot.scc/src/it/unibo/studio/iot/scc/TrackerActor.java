package it.unibo.studio.iot.scc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

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
	private boolean vertical;
	private int flipped;
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
	private Map<Integer, TrackedItem> tracked;
	private Map<Integer, boolean[]> crossed;

	// quantization of hue and value
	private int q_hue, q_value;

	public TrackerActor(double i, double o, boolean v, int f, ActorRef actor) {
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
		this.tracked = new HashMap<Integer, TrackedItem>();
		this.max_distance_radius = 25; // radius in which to find nearest
										// neighbors when matching blobs

		// quantization of hue and value
		// these values should be decided by the user depending on the camera
		this.q_hue = 5;
		this.q_value = 16;
	}

	public static Props props(double i, double o, boolean v, int f, ActorRef a) {
		return Props.create(TrackerActor.class, i, o, v, f, a);
	}

	@Override
	public Receive createReceive() {
		return receiveBuilder().match(UpdateTracking.class, r -> {

			/*
			 * updated_blobs.forEach((id, updated) -> {
			 * updated_blobs.replace(id, false); });
			 */

			// if some blobs are already being tracked
			// try to associate new ones with old ones
			// removing found ones from the "new" list
			if (!tracked.isEmpty()) {
				// there are blobs in the tracked zone already being tracked
				tracked.forEach((id, item) -> {
					// controllo se esistono blob nel nuovo frame con centroide
					// vicino
					int best_candidate = -1;
					double best_candidate_distance = 100000;

					// find the closest blob in the previous frame within a
					// certain radius
					// from b
					for (int i = 0; i < r.getBlobs().size(); i++) {
						double CB = distance(r.getBlobs().get(i).getCentroid(), item.getBlob().getCentroid());
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
							int[] alive_blobCV = new int[item.getBlob().getCV().length];
							int[] new_blobCV = new int[item.getBlob().getCV().length];
							for (int j = 0; j < item.getBlob().getCV().length; j++) {
								if (item.getBlob().usesHUEVector())
									alive_blobCV[j] = item.getBlob().getCV()[j] / this.q_hue;
								else
									alive_blobCV[j] = item.getBlob().getCV()[j] / this.q_value;
								if (r.getBlobs().get(i).usesHUEVector())
									new_blobCV[j] = r.getBlobs().get(i).getCV()[j] / this.q_hue;
								else
									new_blobCV[j] = r.getBlobs().get(i).getCV()[j] / this.q_value;
							}

							if (Arrays.equals(alive_blobCV, new_blobCV))
								candidates.add(i);
						}

						// if something is found
						if (candidates.size() > 0) {
							// find the closest one
							for (int i = 0; i < r.getBlobs().size(); i++) {
								double CB = distance(r.getBlobs().get(candidates.get(i)).getCentroid(),
										item.getBlob().getCentroid());
								if (CB < best_candidate_distance) {
									best_candidate = i;
									best_candidate_distance = CB;
								}

							}
						}

					}
					// either by centroid or by color vector something has been
					// found
					// time to update the tracking

					if (best_candidate != -1) {
						Blob candidate = r.getBlobs().remove(best_candidate);
						item.updateBlob(candidate);
						pos_history.get(item.getID()).add(candidate.getCentroid());
					}

				});
			}

			// se sono rimaste delle blob nella lista che non sono state
			// associate a quelle già presenti, devo decidere cosa farne
			// se sono fuori dall'area di tracking le ignoro, se sono
			// all'interno inizio a tracciarle come nuove blobs

			for (Blob b : r.getBlobs()) {
				// if the blob is in the tracking area add it to the alive
				// list
				// find which baseline it did cross using distance
				if (this.inTrackingArea(b.getCentroid())) {
					// blob list is empty so we fill it with the blobs that
					// crossed one of the baselines

					// generate id for this blob
					//b.setID(generateID());
					int id = generateID();
					// create the list for position history
					pos_history.put(id, new ArrayList<Point>());
					// add first position
					pos_history.get(id).add(b.getCentroid());
					// add the blob to the alive(tracked) list
					tracked.put(id, new TrackedItem(b, id, this.closestBaseline(b.getCentroid())));
					// add an entry for the crossed map
					this.crossed.put(id, new boolean[2]);
					// find closer baseline
					// and set true to index 0 if IN is closer than OUT,
					// true on index 1 on the opposite
					this.crossed.get(id)[this.closestBaseline(b.getCentroid())] = true;

				}

			}

			// now it's time to check if alive blobs have crossed another
			// baseline and do the counting
			Stack<Integer> deathrow = new Stack<Integer>();
			tracked.forEach((id, item) -> {
				if (!inTrackingArea(item.getBlob().getCentroid())) {
					deathrow.push(id);
					if (crossed.get(id)[closestBaseline(item.getBlob().getCentroid())]) {
						// blob is going back => don't count and delete
						// log.info("Blob with ID " +
						// Integer.toString(blob.id()) + " has left the scene
						// going back to its origin. Not counting. ");
					} else {
						// blob is crossing the second baseline => count and
						// delete

						// call closest baseline
						// if 0 the blob is entering the building
						// else is leaving
						if (closestBaseline(item.getBlob().getCentroid()) == 0) {
							// count in
							log.info("Blob with ID " + Integer.toString(item.getID()) + " has left the scene. Counting "
									+ Integer.toString(item.getBlob().weight() * flipped));
							this.counterActor.tell(new Count(item.getBlob().weight() * flipped), this.getSelf());

						} else {
							// count out
							log.info("Blob with ID " + Integer.toString(item.getID()) + " has left the scene. Counting "
									+ Integer.toString(-item.getBlob().weight() * flipped));
							this.counterActor.tell(new Count(-item.getBlob().weight() * flipped), this.getSelf());
						}

					}

					// log.info("Current number of alive blobs: " +
					// Integer.toString(alive_blobs.size() - deathrow.size()));

				}
			});

			// clear dead blobs
			while (!deathrow.isEmpty()) {
				int id = deathrow.pop();
				tracked.remove(id);
				this.pos_history.remove(id);
				this.crossed.remove(id);
			}

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
		while (tracked.containsKey(counter)) {
			counter++;
			if (counter > 20)
				counter = 0;
		}
		return counter;
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
