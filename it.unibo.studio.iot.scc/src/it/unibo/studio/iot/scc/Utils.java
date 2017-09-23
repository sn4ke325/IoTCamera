package it.unibo.studio.iot.scc;

import org.opencv.core.Point;
import org.opencv.core.Rect;

public class Utils {
	public static Rect intersect(Rect r1, Rect r2) {
		int left = Math.max(r1.x, r2.x);
		int top = Math.max(r1.y, r2.y);
		int right = Math.min(r1.x + r1.width, r2.x + r2.width);
		int bottom = Math.min(r1.y + r1.height, r2.y + r2.height);
		if (left <= right && top <= bottom) {
			return new Rect(left, top, right - left, bottom - top);
		}
		return new Rect();
	}

	public static double distance(Point a, Point b) {
		return Math.sqrt(Math.pow(a.x - b.x, 2) + Math.pow(a.y - b.y, 2));
	}
}
