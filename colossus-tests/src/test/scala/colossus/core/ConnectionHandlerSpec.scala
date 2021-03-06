package colossus

import testkit._
import core._
import service.{Service, AsyncServiceClient}

import akka.actor._
import akka.testkit.TestProbe

import java.net.InetSocketAddress
import scala.concurrent.duration._
import akka.util.ByteString

import ConnectionCommand._
import ConnectionEvent._

class Handler(listener: ActorRef) extends Actor {
  def receive = {
    case m => listener ! m
  }
}
  
class AsyncDelegator(props: Props, server: ServerRef, worker: WorkerRef)(implicit factory: ActorRefFactory) extends Delegator(server, worker) {
  implicit val w = worker.worker
  def acceptNewConnection = Some(AsyncHandler(factory.actorOf(props), worker))
}
object AsyncDelegator {
  def factorize(props: Props)(implicit system: ActorSystem): Delegator.Factory = {
    (server, worker) => new AsyncDelegator(props, server, worker)
  }
}

class ConnectionHandlerSpec extends ColossusSpec {

  def createHandlerServer(): (ServerRef, AsyncServiceClient[ByteString, ByteString], TestProbe) = {
    val probe = TestProbe()
    val props = Props(classOf[Handler], probe.ref)
    val server = createServer(AsyncDelegator.factorize(props))
    val c = TestClient(server.system, TEST_PORT)
    probe.expectMsg(ConnectionEvent.Bound(1))
    probe.expectMsg(ConnectionEvent.Connected)
    (server, c, probe)
  }

  "Server Connection Handler" must {
    "bind to worker on creation" in {
      val probe = TestProbe()
      class MyHandler extends BasicSyncHandler {
        override def onBind() {
          probe.ref ! "BOUND"
        }
        def receivedData(data: DataBuffer){}
      }
      withIOSystem{ implicit io =>
        withServer(Server.basic("test", TEST_PORT, () => new MyHandler)) {
          val c = TestClient(io, TEST_PORT)
          probe.expectMsg(100.milliseconds, "BOUND")
        }
      }
    }

    "unbind on disconnect" in {
      val probe = TestProbe()
      class MyHandler extends BasicSyncHandler {
        override def onUnbind() {
          probe.ref ! "UNBOUND"
        }
        def receivedData(data: DataBuffer){}
      }
      withIOSystem{ implicit io =>
        withServer(Server.basic("test", TEST_PORT, () => new MyHandler)) {
          val c = TestClient(io, TEST_PORT)
          c.disconnect()
          probe.expectMsg(100.milliseconds, "UNBOUND")
        }
      }
    }
  }

  "Client Connection Handler" must {

    "bind to worker" in {
      val probe = TestProbe()
      class MyHandler extends BasicSyncHandler with ClientConnectionHandler{
        override def onBind() {
          probe.ref ! "BOUND"
        }
        def receivedData(data: DataBuffer){}
        def connectionFailed(){}
      }
      withIOSystem{ implicit io =>
        //obvious this will fail to connect, but we don't care here
        io ! IOCommand.Connect(new InetSocketAddress("localhost", TEST_PORT), worker => new MyHandler)
        probe.expectMsg(100.milliseconds, "BOUND")
      }
    }

    "not automatically unbind" in {
      val probe = TestProbe()
      class MyHandler extends BasicSyncHandler with ClientConnectionHandler{
        override def onUnbind() {
          probe.ref ! "UNBOUND"
        }

        override def connected(endpoint: WriteEndpoint) {
          endpoint.disconnect()
        }
        def receivedData(data: DataBuffer){}
        def connectionFailed(){}
      }
      withIOSystem{ implicit io =>
        import RawProtocol._
        withServer(Service.become[Raw]("test", TEST_PORT){case x => x}) {
          io ! IOCommand.Connect(new InetSocketAddress("localhost", TEST_PORT), worker => new MyHandler)
          probe.expectNoMsg(200.milliseconds)
        }
      }
    }

    "automatically unbind with AutoUnbindHandler mixin" in {
      val probe = TestProbe()
      class MyHandler extends BasicSyncHandler with ClientConnectionHandler with AutoUnbindHandler{
        override def onUnbind() {
          probe.ref ! "UNBOUND"
        }

        override def connected(endpoint: WriteEndpoint) {
          endpoint.disconnect()
        }
        def receivedData(data: DataBuffer){}
        def connectionFailed(){}
      }
      withIOSystem{ implicit io =>
        import RawProtocol._
        withServer(Service.become[Raw]("test", TEST_PORT){case x => x}) {
          io ! IOCommand.Connect(new InetSocketAddress("localhost", TEST_PORT), worker => new MyHandler)
          probe.expectMsg(200.milliseconds, "UNBOUND")
        }
      }

    }

  }


  "Async Server Handler" must {
    "receive connected event" in {
      val (server, con, probe) = createHandlerServer()
      end(server)
    }
    "receive connection lost event" in {
      val (server, con, probe) = createHandlerServer()
      con.disconnect()
      probe.expectMsgType[ConnectionEvent.ConnectionTerminated]
      end(server)
    }
    "receive data event" in {
      val (server, con, probe) = createHandlerServer()
      con.send(ByteString("HELLO WORLD"))
      probe.expectMsgPF(100.milliseconds){
        case ConnectionEvent.ReceivedData(data) if (data == ByteString("HELLO WORLD")) => true
      }
      end(server)
    }
    "send data back to client" in {
      val (server, con, probe) = createHandlerServer()
      con.send(ByteString("ECHO"))
      probe.expectMsg(ReceivedData(ByteString("ECHO")))
      end(server)
    }

  }


}

