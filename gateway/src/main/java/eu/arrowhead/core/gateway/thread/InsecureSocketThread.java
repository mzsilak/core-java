package eu.arrowhead.core.gateway.thread;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

import eu.arrowhead.common.exception.ArrowheadException;
import eu.arrowhead.common.messages.ConnectToProviderRequest;
import eu.arrowhead.core.gateway.GatewayService;
import eu.arrowhead.core.gateway.model.GatewaySession;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;

import org.apache.log4j.Logger;

public class InsecureSocketThread extends Thread {

	private GatewaySession gatewaySession;
	private String queueName;
	private String controlQueueName;
	private ConnectToProviderRequest connectionRequest;
	private Socket providerSocket;
	private static final Logger log = Logger.getLogger(InsecureSocketThread.class.getName());

	public InsecureSocketThread(GatewaySession gatewaySession, String queueName, String controlQueueName,
			ConnectToProviderRequest connectionRequest) {
		this.gatewaySession = gatewaySession;
		this.queueName = queueName;
		this.controlQueueName = controlQueueName;
		this.connectionRequest = connectionRequest;
	}

	public void run() {

		try {
			// Creating socket for Provider
			Channel channel = gatewaySession.getChannel();
			providerSocket = new Socket(connectionRequest.getProvider().getAddress(),
					connectionRequest.getProvider().getPort());
			providerSocket.setSoTimeout(connectionRequest.getTimeout());
			InputStream inProvider = providerSocket.getInputStream();
			OutputStream outProvider = providerSocket.getOutputStream();
			log.info("Create socket for Provider");

			// Receiving messages through AMQP Broker

			Consumer consumer = new DefaultConsumer(channel) {
				@Override
				public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties,
						byte[] body) throws IOException {
					outProvider.write(body);
					log.info("Sending the request to Provider");
					// get the answer from Provider
					byte[] inputFromProvider = new byte[1024];
					byte[] inputFromProviderFinal = new byte[inProvider.read(inputFromProvider)];
					System.arraycopy(inputFromProvider, 0, inputFromProviderFinal, 0, inputFromProviderFinal.length);
					log.info("Sending the response to Consumer");
					channel.basicPublish("", queueName.concat("_resp"), null, inputFromProviderFinal);
				}
			};

			Consumer controlConsumer = new DefaultConsumer(channel) {
				@Override
				public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties,
						byte[] body) {
					if (new String(body).equals("close")) {
						GatewayService.providerSideClose(gatewaySession, providerSocket);
					}
				}
			};

			while (true) {
				channel.basicConsume(queueName, true, consumer);
				channel.basicConsume(controlQueueName, true, controlConsumer);
			}

		} catch (IOException | NegativeArraySizeException e) {
			log.error("Communication failed (Error occurred or remote peer closed the socket)");
			GatewayService.providerSideClose(gatewaySession, providerSocket);
			throw new ArrowheadException(HttpURLConnection.HTTP_INTERNAL_ERROR, e.getMessage(), e);
		}
	}

}