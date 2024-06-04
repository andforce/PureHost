package top.nicelee.purehost.vpn;

import android.net.VpnService;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.regex.Matcher;

import top.nicelee.purehost.vpn.config.ConfigReader;
import top.nicelee.purehost.vpn.dns.DnsPacket;
import top.nicelee.purehost.vpn.dns.Question;
import top.nicelee.purehost.vpn.dns.ResourcePointer;
import top.nicelee.purehost.vpn.ip.CommonMethods;
import top.nicelee.purehost.vpn.ip.IPHeader;
import top.nicelee.purehost.vpn.ip.TCPHeader;
import top.nicelee.purehost.vpn.ip.UDPHeader;
import top.nicelee.purehost.vpn.server.NATSession;
import top.nicelee.purehost.vpn.server.NATSessionManager;
import top.nicelee.purehost.vpn.server.TCPServer;
import top.nicelee.purehost.vpn.server.UDPServer;

public class LocalVpnService {

    private static final String TAG = "LocalVpnService";

    String localIP = "168.168.168.168";
    int intLocalIP = CommonMethods.ipStringToInt(localIP);
    TCPServer tcpServer;
    UDPServer udpServer;

    boolean isClosed = false;

    public void stopVPN() {
        if (isClosed)
            return;

        Log.d(TAG, "销毁程序调用中...");

        udpServer.stop();
        //tcpServer.stop();
        isClosed = true;
    }


    public void onCreate(VpnService vpnService) {
        isClosed = false;

        tcpServer = new TCPServer(vpnService, localIP);
        udpServer = new UDPServer(vpnService, localIP);

        //tcpServer.start();
        udpServer.start();
    }


    public void onTCPPacketReceived(FileOutputStream vpnOutput, TCPHeader m_TCPHeader, IPHeader ipHeader, int size) throws IOException {
        m_TCPHeader.m_Offset = ipHeader.getHeaderLength();

        Log.d(TAG, "LocalVpnService: TCP消息:" + ipHeader.toString() + "tcp: " + m_TCPHeader.toString());
        if (ipHeader.getDestinationIP() == CommonMethods.ipStringToInt(tcpServer.localIP)) {
            //来自TCP服务器
            NATSession session = NATSessionManager.getSession(m_TCPHeader.getDestinationPort());
            if (session != null) {
                ipHeader.setSourceIP(session.RemoteIP);
                m_TCPHeader.setSourcePort(session.RemotePort);
                ipHeader.setDestinationIP(intLocalIP);

                CommonMethods.ComputeTCPChecksum(ipHeader, m_TCPHeader);
                vpnOutput.write(ipHeader.m_Data, ipHeader.m_Offset, size);
            } else {
                Log.d(TAG, "NoSession:" + ipHeader + ", " + m_TCPHeader);
            }
        } else {
            //来自本地
            // 添加端口映射
            int portKey = m_TCPHeader.getSourcePort();
            NATSession session = NATSessionManager.getSession(portKey);
            if (session == null || session.RemoteIP != ipHeader.getDestinationIP() || session.RemotePort != m_TCPHeader.getDestinationPort()) {
                session = NATSessionManager.createSession(portKey, ipHeader.getDestinationIP(), m_TCPHeader.getDestinationPort());
                Log.d(TAG, "LocalVpnService Session: key Port: " + portKey);
                Log.d(TAG, "LocalVpnService Session: ip : " + CommonMethods.ipIntToString(ipHeader.getDestinationIP()));
                Log.d(TAG, "LocalVpnService Session: port : " + (int) (m_TCPHeader.getDestinationPort()));
            }
            ipHeader.setSourceIP(CommonMethods.ipStringToInt(tcpServer.localIP));
            //tcpHeader.setSourcePort((short)13221);
            ipHeader.setDestinationIP(intLocalIP);
            m_TCPHeader.setDestinationPort((short) tcpServer.port);
            CommonMethods.ComputeTCPChecksum(ipHeader, m_TCPHeader);
            vpnOutput.write(ipHeader.m_Data, ipHeader.m_Offset, size);
        }
    }

    public void onUDPPacketReceived(FileOutputStream vpnOutput, UDPHeader m_UDPHeader, ByteBuffer m_DNSBuffer, IPHeader ipHeader, int size) {
        m_UDPHeader.m_Offset = ipHeader.getHeaderLength();
        int originIP = ipHeader.getSourceIP();
        short originPort = m_UDPHeader.getSourcePort();
        int dstIP = ipHeader.getDestinationIP();
        short dstPort = m_UDPHeader.getDestinationPort();

        Log.d(TAG, "LocalVpnService: 收到一个转发到UDPServer的包, 来源: " + m_UDPHeader.getSourcePort() + ", 目的端口:" + ((int) m_UDPHeader.getDestinationPort()));

        //本地报文, 转发给本地UDP服务器
        //if (ipHeader.getSourceIP() == intLocalIP  && udpHeader.getSourcePort() != udpServer.port) {
        if (ipHeader.getDestinationIP() != CommonMethods.ipStringToInt(udpServer.localIP)) {
            try {
                m_DNSBuffer.clear();
                m_DNSBuffer.limit(ipHeader.getDataLength() - 8);
                DnsPacket dnsPacket = DnsPacket.FromBytes(m_DNSBuffer);
                //Short dnsId = dnsPacket.Header.getID();

                boolean isNeedPollution = false;
                Question question = dnsPacket.Questions[0];
                Log.d(TAG, "DNS 查询的地址是:" + question.Domain);
                String ipAddr = ConfigReader.domainIpMap.get(question.Domain);
                if (ipAddr != null) {
                    isNeedPollution = true;
                } else {
                    Matcher matcher = ConfigReader.patternRootDomain.matcher(question.Domain);
                    if (matcher.find()) {
                        Log.d(TAG, "DNS 查询的地址根目录是: " + matcher.group(1));
                        ipAddr = ConfigReader.rootDomainIpMap.get(matcher.group(1));
                        if (ipAddr != null) {
                            isNeedPollution = true;
                        }
                    }
                }
                if (isNeedPollution) {
                    createDNSResponseToAQuery(m_UDPHeader.m_Data, dnsPacket, ipAddr);

                    ipHeader.setTotalLength(20 + 8 + dnsPacket.Size);
                    m_UDPHeader.setTotalLength(8 + dnsPacket.Size);

                    ipHeader.setSourceIP(dstIP);
                    m_UDPHeader.setSourcePort(dstPort);
                    ipHeader.setDestinationIP(originIP);
                    m_UDPHeader.setDestinationPort(originPort);

                    CommonMethods.ComputeUDPChecksum(ipHeader, m_UDPHeader);
                    vpnOutput.write(ipHeader.m_Data, ipHeader.m_Offset, ipHeader.getTotalLength());
                } else {
                    if (NATSessionManager.getSession(originPort) == null) {
                        NATSessionManager.createSession(originPort, dstIP, dstPort);
                    }
                    ipHeader.setSourceIP(CommonMethods.ipStringToInt("7.7.7.7"));
                    //udpHeader.setSourcePort(originPort);
                    ipHeader.setDestinationIP(intLocalIP);
                    m_UDPHeader.setDestinationPort((short) udpServer.port);

                    ipHeader.setProtocol(IPHeader.UDP);
                    CommonMethods.ComputeUDPChecksum(ipHeader, m_UDPHeader);


                    vpnOutput.write(ipHeader.m_Data, ipHeader.m_Offset, ipHeader.getTotalLength());
                    Log.d(TAG, "LocalVpnService: 本地UDP信息转发给服务器:" + ipHeader + "udp端口" + udpServer.port + "session: " + originPort);
                }
            } catch (Exception e) {
                Log.d(TAG, "当前udp包不是DNS报文");
            }
        } else {
            Log.d(TAG, "LocalVpnService: 其它UDP信息,不做处理:" + ipHeader);
            Log.d(TAG, "LocalVpnService: 其它UDP信息,不做处理:" + m_UDPHeader);
            //vpnOutput.write(ipHeader.m_Data, ipHeader.m_Offset, ipHeader.getTotalLength());
        }
    }

    public void createDNSResponseToAQuery(byte[] rawData, DnsPacket dnsPacket, String ipAddr) {
        Question question = dnsPacket.Questions[0];

        dnsPacket.Header.setResourceCount((short) 1);
        dnsPacket.Header.setAResourceCount((short) 0);
        dnsPacket.Header.setEResourceCount((short) 0);

        ResourcePointer rPointer = new ResourcePointer(rawData, question.Offset() + question.Length());
        rPointer.setDomain((short) 0xC00C);
        rPointer.setType(question.Type);
        rPointer.setClass(question.Class);
        rPointer.setTTL(300);
        rPointer.setDataLength((short) 4);
        rPointer.setIP(CommonMethods.ipStringToInt(ipAddr));

        dnsPacket.Size = 12 + question.Length() + 16;

    }

    public void sendUDPPacket(FileOutputStream vpnOutput, IPHeader ipHeader, UDPHeader udpHeader) {
        try {
            CommonMethods.ComputeUDPChecksum(ipHeader, udpHeader);
            vpnOutput.write(ipHeader.m_Data, ipHeader.m_Offset, ipHeader.getTotalLength());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}