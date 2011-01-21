package org.mobicents.ext.javax.sip.dns;

import gov.nist.javax.sip.stack.HopImpl;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import javax.sip.ListeningPoint;
import javax.sip.address.Hop;
import javax.sip.address.SipURI;
import javax.sip.address.TelURL;
import javax.sip.address.URI;

import org.apache.log4j.Logger;
import org.mobicents.ext.javax.sip.utils.Inet6Util;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.NAPTRRecord;
import org.xbill.DNS.SRVRecord;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

/**
 * The Address resolver to resolve proxy domain to a hop to the outbound proxy server 
 * by doing SRV lookup of the host of the Hop as mandated by rfc3263. <br/>
 * 
 * some of the rfc3263 can hardly be implemented and NAPTR query can hardly be done 
 * since the stack populate port and transport automatically.
 * 
 * @author M. Ranganathan
 * @author J. Deruelle
 *
 */
public class DNSServerLocator {
	private static final Logger logger = Logger.getLogger(DNSServerLocator.class);
	
	protected Set<String> supportedTransports;
	protected Set<String> localHostNames;
	protected DNSLookupPerformer dnsLookupPerformer;
	
	/**
	 */
	public DNSServerLocator(Set<String> supportedTransports) {
		this.supportedTransports = new CopyOnWriteArraySet<String>(supportedTransports);
		localHostNames = new CopyOnWriteArraySet<String>();
	}

	/**
	 * 
	 * @param uri
	 * @return
	 */
	public Queue<Hop> locateHops(URI uri) {
		if(uri.isSipURI()) {
			return locateHopsForSipURI((SipURI)uri);
		} else if(uri instanceof TelURL) {
			return locateHopsForTelURI((TelURL)uri);
		}
		return new LinkedList<Hop>();
	}
	
	public Queue<Hop> locateHopsForTelURI(TelURL telURL) {
		Queue<Hop> priorityQueue = new LinkedList<Hop>();
		return priorityQueue;
	}
	
	public Queue<Hop> locateHopsForSipURI(SipURI sipURI) {		
		
		final String hopHost = sipURI.getHost();
		int hopPort = sipURI.getPort();
		final String hopTransport = sipURI.getTransportParam();	
		
		if(logger.isDebugEnabled()) {
			logger.debug("Resolving " + hopHost + " transport " + hopTransport);
		}
		// As per rfc3263 Section 4.2 
		// If TARGET is a numeric IP address, the client uses that address. 
		if(Inet6Util.isValidIP6Address(hopHost) 
				|| Inet6Util.isValidIPV4Address(hopHost)) {
			if(logger.isDebugEnabled()) {
				logger.debug("host " + hopHost + " is a numeric IP address, " +
						"no DNS SRV lookup to be done, using the hop given in param");
			}
			Queue<Hop> priorityQueue = new LinkedList<Hop>();
			String transport = hopTransport;
			if(transport == null) {
				transport = getDefaultTransportForSipUri(sipURI);
			}			 
			// If the URI also contains a port, it uses that port.  If no port is
			// specified, it uses the default port for the particular transport
			// protocol.numeric IP address, no DNS lookup to be done
			if(hopPort == -1) {		
				if(ListeningPoint.TLS.equals(transport) || (ListeningPoint.TCP.equals(transport) && sipURI.isSecure())) {
					hopPort = 5061;
				} else {
					hopPort = 5060;
				}
			}
			priorityQueue.add(new HopImpl(hopHost, hopPort, transport));
			return priorityQueue;
		} 
		
		// if the host belong to the local endpoint, server or container, it tries to resolve the ip address		
		if(localHostNames.contains(hopHost)) {
			try {
				InetAddress ipAddress = InetAddress.getByName(hopHost);
				Queue<Hop> priorityQueue = new LinkedList<Hop>();
				priorityQueue.add(new HopImpl(ipAddress.getHostAddress(), hopPort, hopTransport));
				return priorityQueue;
			} catch (UnknownHostException e) {
				logger.warn(hopHost + " belonging to the container cannot be resolved");
			}			
		}
				
		// As per rfc3263 Section 4.2
		// If the TARGET was not a numeric IP address, and no port was present
		// in the URI, the client performs an SRV query
		return resolveHostByDnsSrvLookup(sipURI);
		
	}
	
	/**
	 * Resolve the Host by doing a SRV lookup on it 
	 * 
	 * @param sipUri
	 * @return 
	 */
	public Queue<Hop> resolveHostByDnsSrvLookup(SipURI sipURI) {		
		
		final String host = sipURI.getHost();
		final int port = sipURI.getPort();				
		String transport = sipURI.getTransportParam();

		NAPTRRecord naptrRecordOfTransportLookup = null;
		SRVRecord[] srvRecordsOfTransportLookup = null;
		// Determine the transport to be used for a given SIP URI as defined by 
		// RFC 3263 Section 4.1 Selecting a Transport Protocol
		if(transport == null) {
			// Similarly, if no transport protocol is specified,
			// and the TARGET is not numeric, but an explicit port is provided, the
			// client SHOULD use UDP for a SIP URI, and TCP for a SIPS URI
			if(port != -1) {
				transport = getDefaultTransportForSipUri(sipURI);
			} else {
				// Otherwise, if no transport protocol or port is specified, and the
				// target is not a numeric IP address, the client SHOULD perform a NAPTR
				// query for the domain in the URI.
				List<NAPTRRecord> naptrRecords = DNSLookupPerformer.performNAPTRLookup(host, sipURI.isSecure(), supportedTransports);
				
				if(naptrRecords.size() == 0) {
					// If no NAPTR records are found, the client constructs SRV queries for
					// those transport protocols it supports, and does a query for each.
					// Queries are done using the service identifier "_sip" for SIP URIs and
					// "_sips" for SIPS URIs
					Iterator<String> supportedTransportIterator = supportedTransports.iterator();
					while (supportedTransportIterator.hasNext() && transport == null) {
						 String supportedTransport = supportedTransportIterator
								.next();
						try {
							String serviceIdentifier = "_sip._";
							if (sipURI.isSecure()) {
								serviceIdentifier = "_sips._";
							}
							srvRecordsOfTransportLookup = (SRVRecord[]) new Lookup(serviceIdentifier
									+ supportedTransport.toLowerCase() + "." + host, Type.SRV).run();
							if (srvRecordsOfTransportLookup != null && srvRecordsOfTransportLookup.length > 0) {
								// A particular transport is supported if the query is successful.  
								// The client MAY use any transport protocol it 
								// desires which is supported by the server => we use the first one
								transport = supportedTransport;
							}
						} catch (TextParseException e) {
							logger.error("Impossible to parse the parameters for dns lookup",e);
						}						
					}
					// If no SRV records are found, the client SHOULD use TCP for a SIPS
					// URI, and UDP for a SIP URI
					transport = getDefaultTransportForSipUri(sipURI);
				} else {
					naptrRecordOfTransportLookup = naptrRecords.get(0);
					String service = naptrRecordOfTransportLookup.getService();
					if(service.contains(DNSLookupPerformer.SERVICE_SIPS)) {
						transport = ListeningPoint.TLS;
					} else {
						if(service.contains(DNSLookupPerformer.SERVICE_D2U)) {
							transport = ListeningPoint.UDP;
						} else {
							transport = ListeningPoint.TCP;
						}
					}
				}
			}
		}
		transport = transport.toLowerCase();
		
		// RFC 3263 Section 4.2
		// Once the transport protocol has been determined, the next step is to
		// determine the IP address and port.

		if(port != -1) {
			// If the TARGET was not a numeric IP address, but a port is present in
			// the URI, the client performs an A or AAAA record lookup of the domain
			// name.  The result will be a list of IP addresses, each of which can
			// be contacted at the specific port from the URI and transport protocol
			// determined previously.  The client SHOULD try the first record.  If
			// an attempt should fail, based on the definition of failure in Section
			// 4.3, the next SHOULD be tried, and if that should fail, the next
			// SHOULD be tried, and so on.
			return locateHopsForNonNumericAddressWithPort(host, port, transport);
		} else {			
			if(naptrRecordOfTransportLookup != null) {
				// If the TARGET was not a numeric IP address, and no port was present
				// in the URI, the client performs an SRV query on the record returned
				// from the NAPTR processing of Section 4.1, if such processing was
				// performed.
				SRVRecord[] srvRecords = (SRVRecord[]) new Lookup(naptrRecordOfTransportLookup.getReplacement(), Type.SRV).run();
				if (srvRecords != null && srvRecords.length > 0) {
					return sortSRVRecords(host, transport, srvRecords);
				} else {
					// If no SRV records were found, the client performs an A or AAAA record
					// lookup of the domain name.
					return locateHopsForNonNumericAddressWithPort(host, port, transport);
				}
			} else if(srvRecordsOfTransportLookup == null || srvRecordsOfTransportLookup.length == 0){
				// If it was not, because a transport was specified
				// explicitly, the client performs an SRV query for that specific
				// transport, using the service identifier "_sips" for SIPS URIs.  For a
				// SIP URI, if the client wishes to use TLS, it also uses the service
				// identifier "_sips" for that specific transport, otherwise, it uses "_sip"
				SRVRecord[] srvRecords = null;
				try {
					String serviceIdentifier = "_sip._";
					if (sipURI.isSecure() || transport.equalsIgnoreCase(ListeningPoint.TLS)) {
						serviceIdentifier = "_sips._";
					}
					srvRecords = (SRVRecord[]) new Lookup(serviceIdentifier + transport + "." + host, Type.SRV).run();
				} catch (TextParseException e) {
					logger.error("Impossible to parse the parameters for dns lookup", e);
				}
		
				if (srvRecords == null || srvRecords.length == 0) {
					// If no SRV records were found, the client performs an A or AAAA record
					// lookup of the domain name.
					return locateHopsForNonNumericAddressWithPort(host, port, transport);
				} else {
					return sortSRVRecords(host, transport, srvRecords);
				}
			} else {
				// If the NAPTR processing was not done because no NAPTR
				// records were found, but an SRV query for a supported transport
				// protocol was successful, those SRV records are selected
				return sortSRVRecords(host, transport, srvRecordsOfTransportLookup);
			}
		}			
	}

	/**
	 * @param host
	 * @param transport
	 * @param priorityQueue
	 * @param srvRecords
	 * @return
	 */
	private Queue<Hop> sortSRVRecords(final String host, String transport, SRVRecord[] srvRecords) {
		Queue<Hop> priorityQueue = new LinkedList<Hop>();
		if(srvRecords != null && srvRecords.length > 0) {
			List<SRVRecord> sortedSrvs = Arrays.asList(srvRecords);					
			Collections.sort(sortedSrvs, new SRVRecordComparator());
			
			for (SRVRecord srvRecord : sortedSrvs) {
				int recordPort = srvRecord.getPort();						
				String resolvedName = srvRecord.getTarget().toString();
				try {
					String hostAddress= InetAddress.getByName(resolvedName).getHostAddress();
					if(logger.isDebugEnabled()) {
						logger.debug("Did a successful DNS SRV lookup for host:transport " +
								""+ host + "/" + transport +
								" , Host Name = " + resolvedName +
								" , Host IP Address = " + hostAddress + 
								", Host Port = " + recordPort);
					}				
					priorityQueue.add(new HopImpl(hostAddress, recordPort, transport));
				} catch (UnknownHostException e) {
					logger.error("Impossible to get the host address of the resolved name, " +
							"we are going to just use the domain name directly" + resolvedName, e);
				}
			}		
		}
		
		return priorityQueue;
	}

	/**
	 * @param sipURI
	 * @return
	 */
	private static String getDefaultTransportForSipUri(SipURI sipURI) {
		String transport;
		if(sipURI.isSecure()) {
			transport = ListeningPoint.TCP;
		} else {
			transport = ListeningPoint.UDP;
		}
		return transport;
	}
	
	/**
	 * 
	 * @param host
	 * @param port
	 * @param transport
	 * @return
	 */
	private Queue<Hop> locateHopsForNonNumericAddressWithPort(String host, int port, String transport) {
		Queue<Hop> priorityQueue = new LinkedList<Hop>();
		
		final ARecord[] aRecords = DNSLookupPerformer.performALookup(host);
		if(aRecords != null) {
			for(ARecord aRecord : aRecords) {
				priorityQueue.add(new HopImpl(aRecord.getAddress().getHostAddress(), port, transport));
			}
		}		
		final AAAARecord[] aaaaRecords = DNSLookupPerformer.performAAAALookup(host);
		if(aaaaRecords != null) {
			for(AAAARecord aaaaRecord : aaaaRecords) {
				priorityQueue.add(new HopImpl(aaaaRecord.getAddress().getHostAddress(), port, transport));
			}
		}
		return priorityQueue;
	}
	
	public void addLocalHostName(String localHostName) {
		localHostNames.add(localHostName);
	}
	
	public void removeLocalHostName(String localHostName) {
		localHostNames.remove(localHostName);
	}
	
	public void addSupportedTransport(String supportedTransport) {
		supportedTransports.add(supportedTransport);
	}
	
	public void removeSupportedTransport(String supportedTransport) {
		supportedTransports.add(supportedTransport);
	}
}