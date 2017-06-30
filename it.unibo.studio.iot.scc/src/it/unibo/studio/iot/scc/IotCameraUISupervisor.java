package it.unibo.studio.iot.scc;

import akka.actor.AbstractActor;
import akka.actor.Props;

public class IotCameraUISupervisor extends AbstractActor {
	
	public static Props props(){
		return Props.create(IotCameraUISupervisor.class);
	}

	@Override
	public Receive createReceive() {
		// TODO Auto-generated method stub
		return null;
	}

}
