package cameraNode;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Map.Entry;
import java.util.TreeMap;
//import javax.swing.SwingUtilities;
import javax.swing.Timer;

//import cameraPlayer.CameraPlayer;
//import uk.co.caprica.vlcj.discovery.NativeDiscovery;


public class CameraNode {
	 static final int DEBUG_LEVEL = 1;      
	 static final int WAIT_TIMEOUT = 100;
	 static int BW_TEST_TIMER = (int) (2000 + (Math.round(3000.0*Math.abs(Math.sin((System.currentTimeMillis()))))));
	 //will give random timer >= 2 seconds and <= 4 seconds 
	 
	 protected static String nodeName;
	 protected static String hostName;
	 protected static DatagramSocket uSocket;
	
	 static NetworkDiscovery networkSetup = null;  
	 static Dispatcher dispatcher;
	 static MulticastListenerThread mListenerThread;
	 static UnicastListenerThread uListenerThread;
	 static VmsSimClient vmsClient;
	 static BandwidthSimClientThread bwSimClientThread;

	 static NodeId nodeSuccessor = null;
	 static NodeId swarmLeader = null;
	 static String leaderId = null;  
//	 static CameraPlayer camPlayer = null;

     static boolean hasPriority = false; // not necessary for basic algorithm
	 
	 static int cameraId = -1;
	 static int bwIndex = 3;

	 //static String resolutionString[] = {"1080", "720", "640", "360"};  //for video player display (simulation)

	 static TreeMap<String, NodeId> nodeArray = new TreeMap<String, NodeId>();
	 static int numNodes = 0;
	  
	    public static void main(String[] args) throws java.io.IOException, InterruptedException {
	    	nodeName = args[0];
	    	hostName = args[1];
	    	dispatcher = new Dispatcher(nodeName);
	    	uSocket = dispatcher.getSocket();
	    	mListenerThread = new MulticastListenerThread();
	    	uListenerThread = new UnicastListenerThread();
	    	mListenerThread.start();
	        uListenerThread.start();
	        uListenerThread.setSocket(uSocket);
	        networkSetup = new NetworkDiscovery(nodeName);
	    	bwSimClientThread = new BandwidthSimClientThread(hostName, nodeName);		//Simulation
	        bwSimClientThread.start();													//Simulation
//	        if (DEBUG_LEVEL > 1) displayVideo();										//Simulation
	       
	        dispatcher.broadcastSelf("registration: ");

	        Timer bwCheckTimer = new Timer (BW_TEST_TIMER, e -> requestUploadBandwidth());
			bwCheckTimer.setInitialDelay(2000);
			bwCheckTimer.start();			
	}
		/////////////////////Bandwidth control methods /////////////////////

	private static void checkBandwidth(int availableUploadBandwidth ) throws InterruptedException{
		if (hasPriority == false) { 
			String request;
        	if (availableUploadBandwidth < 0 && bwIndex < 3) request = "bwChangeRequest "+cameraId+" "+(bwIndex+1)+" "+true;
         	else if (availableUploadBandwidth > 0 && bwIndex > 0) request = "bwChangeRequest "+cameraId+" "+(bwIndex-1)+" "+true;
         	else return;
        	sendCameraBandwidthChangeRequest (request);
        }
	}
	
	//// main bw control algorithm /////////////////
	 static void checkBwChangeRequest(int camId, int bwRequest, boolean predecessorResponse) throws InterruptedException{ 
		boolean forwardResponse = predecessorResponse;
		
			if (camId != cameraId){
				if (predecessorResponse == true && Math.abs(bwRequest-bwIndex) > 1) forwardResponse = false;
				String request = "bwChangeRequest "+camId+" "+bwRequest+" "+forwardResponse;
				sendCameraBandwidthChangeRequest (request);
			}
			else if (predecessorResponse == true) changeBandwidth (bwRequest);  // full loop of request returned to requesting node and approved
	 }
	
	 static void changeBandwidth(int returnedBWIndex) {
		if (returnedBWIndex != -1) {
			if (returnedBWIndex != bwIndex){
				bwIndex = returnedBWIndex;				
				adjustCameraBandwidth();
			}                
		}
	}	   
	     
	  private static void sendCameraBandwidthChangeRequest (String request) throws InterruptedException{
		  if (nodeArray == null || leaderId == null || nodeSuccessor == null) return;    						// protect against rediscovery
		   
	      InetAddress destination = nodeSuccessor.address;
	      int port = nodeSuccessor.port;				
		  dispatcher.sendMessage(request, destination, port);		   
	   }
	   
	  private static void adjustCameraBandwidth () {
	         if (bwIndex == -1) bwIndex = 0;  											// camera has priority. -1 was written to cameraFlags
//	         if (DEBUG_LEVEL > 1) switchPlayerBW(bwIndex); 
	         if (DEBUG_LEVEL > 0) System.out.println("Adjusted BwIndex = "+bwIndex);
	         setSimulatorCameraBW ();													//Simulation
	     }
	   

	            /////////////////////////////////////////////////////////////////////////////
	  			////// Methods to handle changes in node topology in the network  ///////////
	            /////////////////////////////////////////////////////////////////////////////
	  static synchronized void addNode (String node, NodeId nodeId) throws InterruptedException, IOException{
		if (!nodeArray.containsKey(node)) {
			nodeArray.put(node, nodeId);
			numNodes = nodeArray.size();
			bwIndex = 3;
			resetCameraNetworkTopo(node);
	        if (vmsClient != null) {
            	vmsClient.sendMessage("shutdown");
            	vmsClient = null;
	        }
            if (DEBUG_LEVEL > 0) System.out.println(node+" Has registered");
		}
        //killTimers();
	   }
	
	  private static void resetCameraNetworkTopo(String node) throws InterruptedException {
	        leaderId = null;
	        swarmLeader = null;
	        nodeSuccessor = null;
	        if (!node.equals(nodeName)) {
	        	dispatcher.broadcastSelf("registration: "); 			// rebroadcast self if addNode was called for a new camera
	        }
	        if (numNodes > 1 && nodeArray.containsKey(nodeName)){
	        	networkSetup.detectSuccessor(nodeArray); 
	            nodeSuccessor = networkSetup.getSuccessor();
		        if (swarmLeader == null) startAnElection();			// swarmLeader could have been set in the meantime
	        }														// by leader election triggered by another node
		}
	  
	  static synchronized void rediscoverNetwork() throws InterruptedException, IOException {   // when a node has gone offline initiate network discovery
		  nodeArray.clear();
		  numNodes = 0;
		  //algo.clearCameraArray();
		  bwIndex = 3;
		  hasPriority = false;
		  //killTimers();
		  dispatcher.broadcastSelf("registration: ");
	  }

	  			///////////////////////////////////////////////////////////////////
    			////            Leader Election Methods                        ////
	  			///////////////////////////////////////////////////////////////////
	  
	  private static void startAnElection() throws InterruptedException { 			//start an election
        if (swarmLeader == null) {    											//if in the meantime a swarmLeader has been elected then abort the new election
        	InetAddress destination = nodeSuccessor.address;
        	int port = nodeSuccessor.port;
        	String messageToSuccessor = "startElection "+nodeArray.get(nodeName).hashCode;
        	dispatcher.sendMessage(messageToSuccessor, destination, port);
        }
      }
	
	  static void startElectionMessage(long hash) throws InterruptedException  {
    	while (!nodeArray.containsKey(nodeName)) Thread.sleep(WAIT_TIMEOUT);
    	final long cameraHash = nodeArray.get(nodeName).hashCode;
        String messageToSuccessor = new String();
        
        while (nodeSuccessor == null) Thread.sleep(WAIT_TIMEOUT);
        if (swarmLeader == null) {    											//if in the meantime a swarmLeader has been elected then abort the new election
        	InetAddress destination = nodeSuccessor.address;
        	int port = nodeSuccessor.port;
        	if (cameraHash > hash)	messageToSuccessor =  "startElection "+cameraHash;
        	else if (cameraHash < hash) messageToSuccessor =  "startElection "+hash;
        	else messageToSuccessor =  "setElected "+nodeName+" with hash "+cameraHash;    // (cameraHash == hash)
             
        	dispatcher.sendMessage(messageToSuccessor, destination, port);
        }
     }
     
     static synchronized void setElectedMessage (String leaderid) throws InterruptedException, IOException {
        if (swarmLeader == null) {   											//if in the meantime a swarmLeader has been elected then abort the new election
        	InetAddress destination = nodeSuccessor.address;
        	int port = nodeSuccessor.port;
        	String messageToSuccessor = new String();

        	if (!leaderid.equals(nodeName)) {
        		leaderId = leaderid;
        		swarmLeader = nodeArray.get(leaderid);
        		messageToSuccessor = "setElected "+leaderId;
        		if (DEBUG_LEVEL > 0) System.out.println ("Elected candidate is "+leaderId);
        		dispatcher.sendMessage(messageToSuccessor, destination, port);
        	}
        	else setSwarmLeader (nodeName);  									// this node is leader
        	assignUniqueCameraIds();
        } 
     }
    
     private synchronized static void assignUniqueCameraIds() {
    	int camId = 0;
		for (Entry<String, NodeId> entry : nodeArray.entrySet()) {
			entry.getValue().cameraId = camId++; 
		}
		cameraId = nodeArray.get(nodeName).cameraId;
		updateSimulatorCamId();													// bwSIm needs camId to handle dropped nodes
     }
     
     private static void setSwarmLeader (String node) throws InterruptedException, IOException {
        if (swarmLeader == null) {    											//if in the meantime a swarmLeader has been elected then abort the new election
        	swarmLeader = nodeArray.get(node);
        	leaderId = node;
        	if (DEBUG_LEVEL > 0) System.out.println ("I am the Leader: "+leaderId);
        	if (node.equals(nodeName)) {
        		vmsClient = new VmsSimClient(hostName);							//Simulation        	
        		String messageToVms = "registerSite: "+serializeNodeArray();
        		vmsClient.sendMessage(messageToVms);
        	}
        }
     }
    
	   private static String serializeNodeArray() {
		String serializedString = new String();
		for (Entry<String, NodeId> entry : nodeArray.entrySet()) {
			serializedString = serializedString+" "+entry.getKey()+" "+entry.getValue().hostName+" "+
		entry.getValue().IP+" "+entry.getValue().port+" "+entry.getValue().cameraId;
		}
		return serializedString;
	 }

//////////////////Simulator interfaces //////////////////////
/////////////////////////////////////////////////////////////
	 
    ////// Video management simulation methods  /////////////////
/*	   
	private static void displayVideo() {
           new NativeDiscovery().discover();
           SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    camPlayer = new CameraPlayer ("Camera "+nodeName+" Display");
                    camPlayer.updateBannerWithBW("360");
                }
            });       
        }
     
	private static void switchPlayerBW (int bwIndex) {
    	while (camPlayer == null)
			try {
				Thread.sleep(WAIT_TIMEOUT);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
        camPlayer.switchCameraBW(bwIndex);
        camPlayer.updateBannerWithBW(resolutionString[bwIndex]);
    }
 */
//////////////// bandwidth simulation methods
	private static void setSimulatorCameraBW (){
			 String messageToSim = "SetCameraBW "+cameraId+" "+bwIndex;
	    	 bwSimClientThread.sendMessage(messageToSim);
	    }
	          
	private static void requestUploadBandwidth() {//throws InterruptedException { 
		     BW_TEST_TIMER = (int) (2000 + (Math.round(3000.0*Math.abs(Math.sin((System.currentTimeMillis())))))); // reset timer
	    	 String messageToSim = "RequestBW";
	    	 bwSimClientThread.sendMessage(messageToSim);
	     }
	public static void receiveUploadBandwidth(int bw) throws InterruptedException { 	//called by bwSimClient; will only indicate >0 or <0
		     checkBandwidth(bw);
	     }
	     
	private static void updateSimulatorCamId() {
	 		String messageToSim = "SetCameraID "+cameraId;
	 		bwSimClientThread.sendMessage(messageToSim);
	     }
 	
}
