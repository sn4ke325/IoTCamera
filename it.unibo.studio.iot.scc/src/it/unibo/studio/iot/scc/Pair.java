package it.unibo.studio.iot.scc;

class Pair<T1, T2> {

	private T1 A;
	private T2 B;

	public Pair(T1 f, T2 s) {
		this.A = f;
		this.B = s;

	}

	public T1 first() {
		return A;
	}

	public T2 second() {
		return B;
	}

	public boolean equals(Pair p) {
		return A.equals(p.first()) && B.equals(p.second());
	}

}