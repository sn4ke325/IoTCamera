package it.unibo.studio.iot.scc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import org.opencv.core.Core;
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

	private Map<Integer, Boolean> updated_blobs;// keeps track of the blobs that
												// got an update due to tracking
	private Map<Integer, List<Integer>> merged_blobs; // maps smaller blobs that
														// got merged into
														// bigger ones to track
														// them if they
														// eventually split
	private Map<Integer, TrackedItem> tracked;

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
		this.updated_blobs = new HashMap<Integer, Boolean>();
		this.merged_blobs = new HashMap<Integer, List<Integer>>();

		this.tracked = new HashMap<Integer, TrackedItem>();
		this.max_distance_radius = 70; // radius in which to find nearest
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
				// Maps the overlapping bounding boxes between old frame and new
				// frame
				Map<Integer, List<Integer>> overlaps_tracked = new HashMap<Integer, List<Integer>>();
				Map<Integer, List<Integer>> overlaps_new = new HashMap<Integer, List<Integer>>();

				tracked.forEach((id, item) -> {
					// controllo se esistono blob nel nuovo frame con
					// overlapping bounding box
					Rect r1 = item.getBlob().getBoundingBox();
					overlaps_tracked.put(id, new ArrayList<Integer>());

					for (int i = 0; i < r.getBlobs().size(); i++) {
						if (!overlaps_new.containsKey(i))
							overlaps_new.put(i, new ArrayList<Integer>());

						Rect r2 = r.getBlobs().get(i).getBoundingBox();
						Rect ri = Utils.intersect(r1, r2);

						if (ri.area() > 0) { // there is overlapping
							overlaps_tracked.get(id).add(i);
							overlaps_new.get(i).add(id);
						}

					}
				});

				// now overlaps contains the ids of each new bounding box that
				// overlaps, for each one

				overlaps_tracked.forEach((id, list) -> {
					switch (list.size()) {
					case 0:
						// check color vector
						// if present find blob with same color vector that is
						// also the closest
						// find a list of blobs with same color vector
						List<Integer> candidates = new ArrayList<Integer>();
						for (int i = 0; i < r.getBlobs().size(); i++) {
							int[] tracked_blobCV = new int[tracked.get(id).getBlob().getCV().length];
							int[] new_blobCV = new int[tracked.get(id).getBlob().getCV().length];
							for (int j = 0; j < tracked.get(id).getBlob().getCV().length; j++) {
								if (tracked.get(id).getBlob().usesHUEVector()) {
									tracked_blobCV[j] = tracked.get(id).getBlob().getCV()[j] / this.q_hue;
									new_blobCV[j] = r.getBlobs().get(i).getCV()[j] / this.q_hue;
								} else {
									tracked_blobCV[j] = tracked.get(id).getBlob().getVV()[j] / this.q_value;
									new_blobCV[j] = r.getBlobs().get(i).getVV()[j] / this.q_value;
								}

							}

							if (Arrays.equals(tracked_blobCV, new_blobCV))
								candidates.add(i);
						}

						if (candidates.size() == 1 && Utils.distance(r.getBlobs().get(candidates.get(0)).getCentroid(),
								tracked.get(id).getBlob().getCentroid()) < this.max_distance_radius) {

							tracked.get(id).updateBlob(r.getBlobs().get(candidates.get(0)));
							r.getBlobs().remove(candidates.get(0));

						} else if (candidates.size() > 1) {
							// pick closest
							int best_candidate = -1;
							double best_candidate_distance = 100000;
							for (int i = 0; i < candidates.size(); i++) {
								double distance = Utils.distance(r.getBlobs().get(candidates.get(i)).getCentroid(),
										tracked.get(id).getBlob().getCentroid());
								if (distance < best_candidate_distance) {
									best_candidate = i;
									best_candidate_distance = distance;
								}
							}

							tracked.get(id).updateBlob(r.getBlobs().get(candidates.get(best_candidate)));
							r.getBlobs().remove(candidates.get(best_candidate));
						} else{
							//nor intersection or matching CV was found
						}

						break;
					case 1:
						// check if not merge case
						break;
					default:
						// split case
						break;
					}
				});

				// there are blobs in the tracked zone already being tracked
				tracked.forEach((id, item) -> {
					// controllo se esistono blob nel nuovo frame con
					// overlapping bounding box

					int best_candidate = -1;
					double best_candidate_distance = 100000;
					// find the closest blob
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
								if (item.getBlob().usesHUEVector()) {
									alive_blobCV[j] = item.getBlob().getCV()[j] / this.q_hue;
									new_blobCV[j] = r.getBlobs().get(i).getCV()[j] / this.q_hue;
								} else {
									alive_blobCV[j] = item.getBlob().getVV()[j] / this.q_value;
									new_blobCV[j] = r.getBlobs().get(i).getVV()[j] / this.q_value;
								}

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
					}

				});

				// overlaps now contains all the blobs boxes that overlaps
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
					// b.setID(generateID());
					int id = generateID();
					// add the blob to the alive(tracked) list
					tracked.put(id, new TrackedItem(b, id, this.closestBaseline(b.getCentroid())));

				}

			}

			// now it's time to check if alive blobs have crossed another
			// baseline and do the counting
			Stack<Integer> deathrow = new Stack<Integer>();
			tracked.forEach((id, item) -> {
				if (!inTrackingArea(item.lastPos())) {
					deathrow.push(id);
					if (item.baseline() != closestBaseline(item.lastPos())) {
						// blob is crossing the second baseline => count and
						// delete

						// call closest baseline
						// if 0 the blob is entering the building
						// else is leaving
						if (item.baseline() == 1) {
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

				} else {// controllo se nella zona di tracking vi sono blob
						// freezate
					if (item.isIdle())
						deathrow.push(id);
				}
			});

			// clear dead blobs
			while (!deathrow.isEmpty()) {
				int id = deathrow.pop();
				tracked.remove(id);
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
