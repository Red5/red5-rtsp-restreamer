package org.red5.demos.rtsp;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.mina.core.buffer.IoBuffer;
import org.red5.demos.util.Base64;
import org.red5.io.amf.Output;
import org.red5.server.net.rtmp.event.IRTMPEvent;
import org.red5.server.net.rtmp.event.Notify;
import org.red5.server.net.rtmp.event.VideoData;
import org.red5.server.net.rtmp.message.Header;

/**
 * Transcode rtsp over tcp and broadcast as flv.
 * 
 * @author Andy Shaules
 */
public class AxisTest implements Runnable {

	public static final int CodedSlice = 1;

	public static final int DataPartitionA = 2;

	public static final int DataPartitionB = 3;

	public static final int DataPartitionC = 4;

	public static final int IDR = 5;

	public static final int SEI = 6;

	public static final int SPS = 7;

	public static final int PPS = 8;

	public static final int AUD = 9;

	public static final int FUA = 28;

	public static final String[] names = { "Undefined", "Coded slice", "Coded slice data partition A", "Coded slice data partition B", "Coded slice data partition C", "IDR", "SEI", "SPS", "PPS", "AUD" };
	
	private static String A = "a";

	private static String B = "b";

	public static int FU8 = 1 << 7;

	public static int FU7 = 1 << 6;

	public static int FU6 = 1 << 5;

	public static int FU5 = 1 << 4;

	public static int FU4 = 1 << 3;

	public static int FU3 = 1 << 2;

	public static int FU2 = 1 << 1;

	public static int FU1 = 1;

	public static int FUupper = FU8 | FU7 | FU6;

	public static int FUlower = FU5 | FU4 | FU3 | FU2 | FU1;

	private Socket requestSocket;

	private OutputStream out;

	private long seq = 0;

	private int state = 0;

	private int bodyCounter = 0;

	private Map<String, String> headers = new HashMap<String, String>();

	private Map<String, String> description = new HashMap<String, String>();

	private int nProfile;

	private int nLevel;

	private int nId;

	private byte[] part1;

	private byte[] part2;

	private int streamTime = -1;

	private long streamCreation;

	private int _codecSetupLength;

	private int[] _pCodecSetup;

	private IRTMPEvent lastKey;

	private List<IRTMPEvent> slices = new ArrayList<IRTMPEvent>();

	private List<int[]> chunks = new ArrayList<int[]>();

	private IEventSink output;

	private boolean doRun = true;

	public AxisTest(IEventSink out) {
		output = out;
	}

	@SuppressWarnings("unused")
	public void run() {

		try {
			requestSocket = new Socket("192.168.10.8", 554);

			System.out.println("Connected to 192.168.10.8 in port 554");

			out = requestSocket.getOutputStream();

			out.write(("DESCRIBE rtsp://myserver/axis-media/media.amp?fps=15 RTSP/1.0\r\n" + "CSeq: " + nextSeq() + "\r\n" + "User-Agent: Axis AMC\r\n" + "Accept: application/sdp\r\n" + "\r\n").getBytes());

			out.flush();

			parseDescription();

			out.write(("SETUP rtsp://myserver/axis-media/media.amp?fps=15 RTSP/1.0\r\n" + "CSeq: " + nextSeq() + "\r\n" + "User-Agent: Axis AMC\r\n" + "Blocksize : 86000\r\n" + "Transport: RTP/AVP/TCP;unicast\r\n\r\n").getBytes());

			out.flush();

			int k = 0;
			String lines = "";
			String sessionIdentifier = "";
			while ((k = requestSocket.getInputStream().read()) != -1) {

				lines += String.valueOf((char) k);
				if (lines.indexOf("\n") > -1) {

					lines = lines.replace("\r", "");
					lines = lines.replace("\n", "");
					if (lines.indexOf("Session") == 0) {
						sessionIdentifier = lines.split("(:)")[1].split("(;)")[0].trim();
					}
					System.out.println(lines);

					if (lines.length() == 0) {
						break;
					}
					lines = "";

				}

			}

			out.write(("PLAY rtsp://myserver/axis-media/media.amp RTSP/1.0\r\n" + "CSeq: " + nextSeq() + "\r\n" + "User-Agent: Axis AMC\r\n" + "Session: " + sessionIdentifier + " \r\n\r\n").getBytes());

			out.flush();
			k = 0;
			lines = "";
			while ((k = requestSocket.getInputStream().read()) != -1) {

				lines += String.valueOf((char) k);
				if (lines.indexOf("\n") > -1) {

					lines = lines.replace("\r", "");
					lines = lines.replace("\n", "");

					System.out.println(lines);

					if (lines.length() == 0) {
						System.out.println("End of header, begin stream.");
						break;

					}
					lines = "";

				}

			}

			int lengthToRead = 0;//incoming packet length. 

			while (doRun && (k = requestSocket.getInputStream().read()) != -1) {

				if (k == 36) {

					int buffer[] = new int[3];
					buffer[0] = requestSocket.getInputStream().read() & 0xff;
					buffer[1] = requestSocket.getInputStream().read() & 0xff;
					buffer[2] = requestSocket.getInputStream().read() & 0xff;

					lengthToRead = (buffer[1] << 8 | buffer[2]);

					int packet[] = new int[lengthToRead];//copy packet.

					k = 0;
					while (lengthToRead-- > 0) {
						packet[k++] = requestSocket.getInputStream().read() & 0xFF;
					}
					//packet header info.
					int cursor = 0;
					int version = (packet[0] >> 6) & 0x3;//rtp
					int p = (packet[0] >> 5) & 0x1;
					int x = (packet[0] >> 4) & 0x1;
					int cc = packet[0] & 0xf;//count of sync srsc
					int m = packet[1] >> 7;//m bit
					int pt = packet[1] & (0xff >> 1);//packet type
					int sn = packet[2] << 8 | packet[3];//sequence number.
					long time = packet[4] << 24 | packet[5] << 16 | packet[6] << 8 | packet[7];
					time = (time / 100);//to milliseconds?
					//first packet.
					if (streamTime == -1) {
						streamTime = (int) time;//set start delta.
						streamCreation = System.currentTimeMillis();
					}
					time = time - streamTime;

					time = System.currentTimeMillis() - streamCreation;

					cursor = 12;
					//parse any other clock sources.
					for (int y = 0; (y < cc); y++) {
						long CSRC = packet[cursor++] << 24 | packet[cursor++] << 16 | packet[cursor++] << 8 | packet[cursor++];
					}
					//based on packet type.
					int type = 0;

					if (pt == 96) {

						int pos = cursor;

						type = readNalHeader((byte) packet[cursor++]);
						IoBuffer buffV;

						int[] realPacket = Arrays.copyOfRange(packet, pos, packet.length);

						switch (type) {

							case FUA:

								byte info = (byte) realPacket[1];
								int fragStart = (info >> 7) & 0x1;
								int fragEnd = (info >> 6) & 0x1;
								int fragmentedType = readNalHeader(info);

								if (fragStart == 1) {
									System.out.print(" fragStart ");
									chunks.clear();

								}
								System.out.print(" . ");
								chunks.add(realPacket);

								if (fragEnd == 1) {

									System.out.print(" fragEnd ");
									System.out.print(" num " + chunks.size());

									if (names.length > fragmentedType) {
										System.out.print(" " + names[fragmentedType]);
									}
									System.out.print("\n");

									int tot = 1;//packet header.
									for (int[] part : chunks) {
										tot += part.length - 2;//shave 2 bytes of packet header from count
									}

									int totBuf[] = new int[tot];
									//set in non-fragmented Nal header.
									totBuf[0] = (realPacket[0] & FUupper) | (realPacket[1] & FUlower);//3 bits of first, 5 bits of second 

									int chunkCursor = 1;

									for (int l = 0; l < chunks.size(); l++) {
										//Aggregate into one array.

										for (int d = 2; d < chunks.get(l).length; d++) {
											totBuf[chunkCursor++] = chunks.get(l)[d];
										}
									}
									type = fragmentedType;
									realPacket = totBuf;
									System.out.print("realPacket " + realPacket.length);
								} else {
									break;
								}

							case IDR://key frame. send config for giggles.							
								sendAVCDecoderConfig((int) time);

								buffV = IoBuffer.allocate(realPacket.length + 9);
								buffV.setAutoExpand(true);
								buffV.put((byte) 0x17);//packet tag// key // avc
								buffV.put((byte) 0x01);//vid tag
								//vid tag presentation off set
								buffV.put((byte) 0);
								buffV.put((byte) 0);
								buffV.put((byte) 0);
								//nal size
								buffV.put((byte) ((realPacket.length >> 24) & 0xff));
								buffV.put((byte) ((realPacket.length >> 16) & 0xff));
								buffV.put((byte) ((realPacket.length >> 8) & 0xff));
								buffV.put((byte) ((realPacket.length) & 0xff));
								//nal data
								for (int r = 0; r < realPacket.length; r++) {
									buffV.put((byte) realPacket[r]);
								}

								buffV.flip();
								buffV.position(0);
								IRTMPEvent videoIDR = new VideoData(buffV);
								videoIDR.setHeader(new Header());
								videoIDR.getHeader().setTimer((int) (time));
								videoIDR.setTimestamp((int) (time));

								//new key frame;
								slices.clear();

								lastKey = videoIDR;

								if (output != null)
									output.dispatchEvent(videoIDR);
								break;

							case CodedSlice:
								buffV = IoBuffer.allocate(realPacket.length + 9);
								buffV.setAutoExpand(true);
								buffV.put((byte) 0x27);//packet tag//non key//avc
								buffV.put((byte) 0x01);//vid tag
								//presentation off set
								buffV.put((byte) 0x0);
								buffV.put((byte) 0);
								buffV.put((byte) 0);
								//nal size
								buffV.put((byte) ((realPacket.length >> 24) & 0xff));
								buffV.put((byte) ((realPacket.length >> 16) & 0xff));
								buffV.put((byte) ((realPacket.length >> 8) & 0xff));
								buffV.put((byte) ((realPacket.length) & 0xff));
								//nal data
								for (int r = 0; r < realPacket.length; r++) {
									buffV.put((byte) realPacket[r]);
								}

								buffV.flip();
								buffV.position(0);

								IRTMPEvent CodedSlice = new VideoData(buffV);
								CodedSlice.setTimestamp((int) (time));
								CodedSlice.setHeader(new Header());
								CodedSlice.getHeader().setTimer((int) (time));
								slices.add(CodedSlice);

								if (output != null)
									output.dispatchEvent(CodedSlice);

								break;
						}
					}
				}
			}

			out.write(("TEARDOWN rtsp://myserver/axis-media/media.amp RTSP/1.0\r\n" + "CSeq: " + nextSeq() + "\r\n" + "User-Agent: Axis AMC\r\n" + "Session: " + sessionIdentifier + " \r\n\r\n").getBytes());

			out.flush();

		} catch (UnknownHostException unknownHost) {
			unknownHost.printStackTrace();
		} catch (IOException ioException) {
			ioException.printStackTrace();
		} finally {

			try {
				requestSocket.close();
			} catch (IOException ioException) {
				ioException.printStackTrace();
			}
		}
	}

	private void parseDescription() throws IOException {
		int k = 0;

		String returnData = "";

		while (bodyCounter == 0 && (k = requestSocket.getInputStream().read()) != -1) {
			//get header
			returnData = returnData + String.valueOf((char) k);
			//read it line by line.
			if (state < 3) {
				if (returnData.indexOf("\n") > -1) {

					parse(returnData);

					returnData = "";
				} else {
					continue;
				}
			} else {
				//got description header.
				break;
			}
		}
		//parse description body.
		int localVal = bodyCounter;
		//get body
		while (localVal-- > 0 && (k = requestSocket.getInputStream().read()) != -1) {

			returnData = returnData + String.valueOf((char) k);
			//read it line by line.
			if (state < 3) {
				if (returnData.indexOf("\n") > -1) {
					parse(returnData);
					returnData = "";
				} else {
					continue;
				}
			} else {
				break;
			}
		}
		//We have the sdp description data.
		System.out.println(description);
		//get the parameter sets
		String sparams = description.get("fmtp").split("(;)")[2];
		String PLI = description.get("fmtp").split("(;)")[1];
		int pE = PLI.indexOf("=");
		PLI = PLI.substring(pE + 1).trim();
		//this info can also be taken out of byte index 1-3 of the sps array.
		String profile = PLI.substring(0, 2);
		String level = PLI.substring(2, 4);
		String id = PLI.substring(4);

		nProfile = Integer.parseInt(profile, 16);
		nLevel = Integer.parseInt(level, 16);
		nId = Integer.parseInt(id, 16);

		int firstE = sparams.indexOf("=");
		String isolated = sparams.substring(firstE + 1);
		String splits[] = isolated.split("(,)");
		//sps
		part1 = Base64.decode(splits[0].trim());
		//pps
		part2 = Base64.decode(splits[1].trim());
		//make the flv key frame.
		produceCodecSetup();
	}

	private long nextSeq() {
		return ++seq;
	}

	private void parseHeader(String s) {

		String[] hdr = new String[2];
		int idx = s.indexOf(":");
		//end of header?
		if (idx < 0) {
			bodyCounter = Integer.valueOf(headers.get("Content-Length"));
			state = 2;
			return;
		}

		hdr[0] = s.substring(0, idx);
		hdr[1] = s.substring(idx + 1);
		hdr[1] = hdr[1].replace("\r", "");
		hdr[1] = hdr[1].replace("\n", "");
		headers.put(hdr[0].trim(), hdr[1].trim());
		System.out.println(hdr[0] + " = " + hdr[1]);
	}

	private int readNalHeader(byte bite) {

		int NALUnitType = (int) (bite & 0x1F);
		return NALUnitType;
	}

	private void produceCodecSetup() {

		_codecSetupLength = 5 //header
				+ 8 //SPS header
				+ part1.length //the SPS itself
				+ 3 //PPS header
				+ part2.length; //the PPS itself

		_pCodecSetup = new int[_codecSetupLength];
		int cursor = 0;
		//header
		_pCodecSetup[cursor++] = 0x17; //0x10 - key frame; 0x07 - H264_CODEC_ID
		_pCodecSetup[cursor++] = 0; //0: AVC sequence header; 1: AVC NALU; 2: AVC end of sequence
		_pCodecSetup[cursor++] = 0; //CompositionTime
		_pCodecSetup[cursor++] = 0; //CompositionTime
		_pCodecSetup[cursor++] = 0; //CompositionTime
		//SPS
		_pCodecSetup[cursor++] = 1; //version
		_pCodecSetup[cursor++] = nProfile; //profile
		_pCodecSetup[cursor++] = nLevel; //profile compat
		_pCodecSetup[cursor++] = nId; //level
		//Curious, are they really one and the same as specified by the Sdp?
		System.out.println("PROFILE IDC " + nProfile + "  " + part1[1]);
		System.out.println("Profile IOP " + nLevel + "  " + part1[2]);
		System.out.println("LEVEL IDC " + nId + "  " + part1[3]);
		_pCodecSetup[cursor++] = 0xff; //6 bits reserved (111111) + 2 bits nal size length - 1 (11)//Adobe does not set reserved bytes.
		_pCodecSetup[cursor++] = 0xe1; //3 bits reserved (111) + 5 bits number of sps (00001)//Adobe does not set reserved bytes.
		//SPS length.
		_pCodecSetup[cursor++] = ((part1.length >> 8) & 0xFF);
		_pCodecSetup[cursor++] = (part1.length & 0xFF);
		//copy _pSPS data;
		for (int k = 0; k < part1.length; k++) {
			_pCodecSetup[cursor++] = part1[k];
		}
		//PPS
		_pCodecSetup[cursor++] = 1; //number of pps. TODO, actually check for more than one pps in the codec private data.
		//short to big endian.
		_pCodecSetup[cursor++] = ((part2.length >> 8) & 0xFF);
		_pCodecSetup[cursor++] = (part2.length & 0xFF);
		//copy _pPPS data;
		for (int k = 0; k < part2.length; k++) {
			_pCodecSetup[cursor++] = part2[k];
		}
	}

	private void parse(String s) {
		if (state == 1) {//RTSP OK 200, get headers.
			parseHeader(s);
			return;
		}
		if (state == 2) {//DESCRIBE results.
			parseDescribeBody(s);
			return;
		}
		if (state == 3) {//Done. Ready for setup/playback.
			return;
		}
		//check for RTSP OK 200
		String[] tokens = s.split(" ");
		for (int i = 0; i < tokens.length; i++) {
			if ((tokens[i].indexOf("200") > -1)) {
				state = 1;
			}
		}
	}

	private void parseDescribeBody(String s) {
		String[] hdr = new String[2];
		int idx = s.indexOf("=");

		if (idx < 0) {//finished with body header?
			state = 3;
			return;
		}
		//drill down into header data.
		hdr[0] = s.substring(0, idx);
		hdr[1] = s.substring(idx + 1);
		hdr[1] = hdr[1].replace("\r", "");
		hdr[1] = hdr[1].replace("\n", "");
		if (A.equals(hdr[0]) || B.equals(hdr[0])) {//media descriptors for h.264
			String[] subParams = hdr[1].split("(:)");
			description.put(subParams[0].trim(), subParams[1].trim());
		} else {
			description.put(hdr[0].trim(), hdr[1].trim());
		}
	}

	public IRTMPEvent getAVCDecoderConfig() {
		IoBuffer buffV = IoBuffer.allocate(_pCodecSetup.length);
		buffV.setAutoExpand(true);
		for (int p = 0; p < _pCodecSetup.length; p++)
			buffV.put((byte) _pCodecSetup[p]);
		buffV.flip();
		buffV.position(0);
		IRTMPEvent video = new VideoData(buffV);
		video.setHeader(new Header());
		return video;
	}

	public synchronized IRTMPEvent getLastKey() {
		return lastKey;
	}

	public synchronized List<IRTMPEvent> getLastSlices() {
		return slices;
	}

	private void sendAVCDecoderConfig(int timecode) {

		IoBuffer buffV = IoBuffer.allocate(_pCodecSetup.length);
		buffV.setAutoExpand(true);
		for (int p = 0; p < _pCodecSetup.length; p++)
			buffV.put((byte) _pCodecSetup[p]);
		buffV.flip();

		buffV.position(0);

		IRTMPEvent video = new VideoData(buffV);

		video.setTimestamp(timecode);

		video.setHeader(new Header());

		if (output != null)
			output.dispatchEvent(video);

	}

	public Notify getMetaDataEvent() {

		IoBuffer buf = IoBuffer.allocate(1024);
		buf.setAutoExpand(true);
		Output out = new Output(buf);
		out.writeString("onMetaData");

		Map<Object, Object> props = new HashMap<Object, Object>();
		props.put("width", 160);
		props.put("height", 120);
		props.put("framerate", 15);
		props.put("videocodecid", 7);
		props.put("canSeekToEnd", false);
		out.writeMap(props);
		buf.flip();

		return new Notify(buf);
	}

	public void setDoRun(boolean doRun) {
		this.doRun = doRun;
	}

	public boolean isDoRun() {
		return doRun;
	}

}
