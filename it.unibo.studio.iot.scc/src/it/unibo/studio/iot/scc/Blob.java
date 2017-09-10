package it.unibo.studio.iot.scc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

public class Blob {
	private MatOfPoint p;
	private double area;
	private Point centroid;
	private Rect boundingBox;
	private int id;
	private boolean alive;
	private int weight; // how many people the blob contains
	private boolean evaluate; // flag that tells if the blob is relevant for
								// counting
	private List<double[]> HSV_data;
	private int[] color_vector;
	private boolean hue_vector;

	public Blob(MatOfPoint points) {
		this.p = points;
		this.boundingBox = computeBoundingBox(p);
		this.area = Imgproc.contourArea(p);
		Moments M = Imgproc.moments(points);
		int cx = (int) (M.m10 / M.m00);
		int cy = (int) (M.m01 / M.m00);
		this.centroid = new Point(cx, cy);
		this.alive = true;
		this.evaluate = false;
	}

	public Blob(MatOfPoint points, Rect box) {
		this(points);
		this.boundingBox = box;
	}

	public void setID(int id) {
		this.id = id;
	}

	public void addHSVData(List<double[]> l) {
		// 0 - hue
		// 1 - saturation
		// 2 - value
		this.HSV_data = l;
		double perc = 0;
		double partial = 0;
		double total = 0;
		// find the perc of pixels that have saturation under 30
		for (int i = 0; i < HSV_data.get(1).length; i++) {
			total += HSV_data.get(1)[i];
			if (i < 20)
				partial += HSV_data.get(1)[i];

		}
		perc = (partial / total);

		if (perc > 0.9) {
			// use Value
			this.color_vector = this.findMaxIndexVector(HSV_data.get(2), 3);
			this.hue_vector = false;
		} else {
			// use Hue
			this.color_vector = this.findMaxIndexVector(HSV_data.get(0), 3);
			this.hue_vector = true;
		}

	}

	public int[] getCV() {
		return color_vector;
	}

	public boolean usesHUEVector() {
		return hue_vector;
	}

	public MatOfPoint getContours() {
		return p;
	}

	public double getArea() {
		return area;
	}

	public Point getCentroid() {
		// return centroid;
		return new Point((2*boundingBox.x + boundingBox.width) / 2, (2*boundingBox.y + boundingBox.height) / 2);
	}

	public Rect getBoundingBox() {
		return boundingBox;
	}

	public int id() {
		return id;
	}

	public void setWeight(int w) {
		this.weight = w;
	}

	public int weight() {
		return weight;
	}

	public boolean isAlive() {
		return alive;
	}

	public void kill() {
		this.alive = false;
	}

	public void setEvaluate(boolean f) {
		this.evaluate = f;
	}

	public boolean evaluate() {
		return evaluate;
	}

	public void update(Blob b) {
		this.p = b.getContours();
		this.area = b.getArea();
		this.centroid = b.getCentroid();
		this.boundingBox = b.getBoundingBox();
		this.weight = b.weight;
	}

	private int[] findMaxIndexVector(double[] v, int n) {
		double[] max = new double[n];
		int[] n_top = new int[n];
		Arrays.fill(max, -1);
		Arrays.fill(n_top, -1);
		for (int i = 0; i < n; i++) {
			if (i == 0) {
				// find absolute max and put it in max[0]
				for (int j = 0; j < v.length; j++) {
					if (v[j] > max[i]) {
						max[i] = v[j];
						n_top[i] = j;
					}
				}
			} else {
				for (int j = 0; j < v.length; j++) {
					if (v[j] > max[i] && v[j] < max[i - 1]) {
						max[i] = v[j];
						n_top[i] = j;
					}
				}
			}
		}

		return n_top;
	}

	private Rect computeBoundingBox(MatOfPoint points) {
		MatOfPoint2f approxCurve = new MatOfPoint2f();
		MatOfPoint2f contour2f = new MatOfPoint2f(points.toArray());
		double approxDistance = Imgproc.arcLength(contour2f, true) * 0.02;
		Imgproc.approxPolyDP(contour2f, approxCurve, approxDistance, true);
		MatOfPoint rect_points = new MatOfPoint(approxCurve.toArray());
		return Imgproc.boundingRect(rect_points);
	}

}
