package it.unibo.studio.iot.scc;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;

//Camera supervisor that will manage all camera functions such as video capturing, analysis and remote communication...

public class IotCameraSupervisor extends AbstractActor {

	private final String groupId;

	private final String deviceId;
	
	private ActorRef uiSupervisor;
	private ActorRef videoAnalysisSupervisor;
	private ActorRef counterSupervisor;

	public IotCameraSupervisor(String groupId, String deviceId) {
		this.groupId = groupId;
		this.deviceId = deviceId;
		
		this.uiSupervisor = this.getContext().actorOf(IotCameraUISupervisor.props());
		
	}

	public static Props props(String groupId, String deviceId) {
		return Props.create(IotCameraSupervisor.class, groupId, deviceId);
	}

	@Override
	public Receive createReceive() {
		// TODO Auto-generated method stub
		return null;
	}
}
