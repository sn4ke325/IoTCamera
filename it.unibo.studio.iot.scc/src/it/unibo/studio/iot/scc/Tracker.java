package it.unibo.studio.iot.scc;

import akka.actor.AbstractActor;
import akka.actor.Props;

public class Tracker extends AbstractActor {

	public void preStart() {

	}

	public static Props props() {
		return Props.create(Tracker.class);
	}

	@Override
	public Receive createReceive() {
		// TODO Auto-generated method stub
		return null;
	}

}
