package it.unibo.studio.iot.scc;

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

	public Blob(MatOfPoint points, int id) {
		this.p = points;
		this.id = id;
		this.boundingBox = computeBoundingBox(p);
		this.area = Imgproc.contourArea(p);
		Moments M = Imgproc.moments(points);
		int cx = (int) (M.m10 / M.m00);
		int cy = (int) (M.m01 / M.m00);
		this.centroid = new Point(cx, cy);
		this.alive = true;
	}

	public Blob(MatOfPoint points, Rect box, int id) {
		this(points, id);
		this.boundingBox = box;
	}

	public MatOfPoint getContours() {
		return p;
	}

	public double getArea() {
		return area;
	}

	public Point getCentroid() {
		return centroid;
	}

	public Rect getBoundingBox() {
		return boundingBox;
	}

	public int id() {
		return id;
	}

	public void kill() {
		this.alive = false;
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
