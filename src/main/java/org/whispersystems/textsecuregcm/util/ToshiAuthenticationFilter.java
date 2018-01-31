package org.whispersystems.textsecuregcm.util;

import java.io.IOException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.Provider;
import javax.ws.rs.WebApplicationException;

import java.lang.reflect.Method;

import java.nio.charset.Charset;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.security.SignatureException;

import org.ethereum.crypto.ECKey;
import org.ethereum.crypto.ECKey.ECDSASignature;
import static org.ethereum.crypto.HashUtil.sha3;
import org.spongycastle.util.encoders.Hex;
import org.spongycastle.jcajce.provider.digest.Keccak.DigestKeccak;
import org.spongycastle.jcajce.provider.digest.Keccak.Digest256;
import java.util.Base64;

import java.util.logging.Logger;
import java.util.logging.Level;

@Provider
@RequiresToshiAuthentication
public class ToshiAuthenticationFilter implements ContainerRequestFilter {

    private static final long TIMESTAMP_EXPIRY = 180;
    private static final int BUF_SIZE = 0x1000; // 4K
    private final Charset UTF8_CHARSET = Charset.forName("UTF-8");
    private static final Logger logger = Logger.getLogger( ToshiAuthenticationFilter.class.getName() );

    private Response buildError(int status, String id, String message) {
        return Response.status(status)
            .entity(String.format("{\"errors\": [{\"id\": \"%s\", \"message\": \"%s\"}", id, message))
            .type("application/json")
            .build();
    }

    private long getServerTime() {
        return System.currentTimeMillis() / 1000L;
    }

    public static long copy(InputStream from, OutputStream to)
        throws IOException {
        if (from == null) { return 0; };
        if (to == null) { return 0; };

        byte[] buf = new byte[BUF_SIZE];
        long total = 0;
        while (true) {
            int r = from.read(buf);
            if (r == -1) {
                break;
            }
            to.write(buf, 0, r);
            total += r;
        }
        return total;
    }

    public String getRecoveredEthAddress(String verb, String path, String body, String timestamp, String rawSignature) throws
        JsonProcessingException,
        SignatureException,
        InvalidComponentsException,
        SignatureLengthException {

        
        //Logger logger = java.util.logging.Logger.getLogger(MyClass.class.getName());
        //logger.setLevel(Level.INFO);

        logger.log(Level.INFO,"verb");
        logger.log(Level.INFO,verb);
        logger.log(Level.FINE,"path");
        logger.log(Level.FINE,path);
        logger.log(Level.FINE,"body");
        logger.log(Level.FINE,body);
        logger.log(Level.FINE,"timestamp");
        logger.log(Level.FINE,timestamp);
        logger.log(Level.FINE,"rawSignature");
        logger.log(Level.FINE,rawSignature);
        

        String hexAddress = null;

        if (rawSignature.length() != 132) {
            throw new SignatureLengthException(rawSignature.length());
        }

        final DigestKeccak keccak = new Digest256();
        keccak.update(body.getBytes());
        byte[] hash = keccak.digest();

        logger.log(Level.INFO,"hash");
        logger.log(Level.INFO,hash.toString());
        byte[] encodedHashBytes = Base64.getEncoder().encode(hash);
        String encodedHash = new String(encodedHashBytes);

        logger.log(Level.FINE,"encodedHash");
        logger.log(Level.FINE,encodedHash);
        String payload = verb+"\n"+path+"\n"+timestamp+"\n"+encodedHash;

        logger.log(Level.FINE,"payload");
        logger.log(Level.FINE,payload);
        byte[] payloadHash = sha3(payload.getBytes());

        logger.log(Level.FINE,"payloadHash");
        logger.log(Level.FINE,payloadHash.toString());
        byte[] sig = Hex.decode(rawSignature.substring(2));

        logger.log(Level.FINE,"sig");
        logger.log(Level.FINE,sig.toString());

        byte[] r = new byte[32];
        System.arraycopy(sig, 0, r, 0, 32);
        System.out.print("r");
        System.out.print(r);
        byte[] s = new byte[32];
        System.arraycopy(sig, 32, s, 0, 32);
        System.out.print("s");
        System.out.print(s);
        byte v = (byte) (sig[64] + 0x1b);
        System.out.print("v");
        System.out.print(v);

        ECDSASignature signature = ECKey.ECDSASignature.fromComponents(r, s, v);

        //logger.log(Level.FINE,"signature");
        //logger.log(Level.FINE,signature);
        if (signature.validateComponents()) {
            byte[] address = ECKey.signatureToAddress(payloadHash, signature);

            logger.log(Level.FINE,"address");
            logger.log(Level.FINE,address.toString());
            hexAddress = "0x" + new String(Hex.encode(address));
        } else {
            throw new InvalidComponentsException();
        }
        
        logger.log(Level.FINE,"hexAddress");
        logger.log(Level.FINE,hexAddress);

        return hexAddress;
    }

    @Override
    public void filter(ContainerRequestContext requestContext)
        throws IOException {

        // get method
        String method = requestContext.getMethod();

        // get path
        UriInfo uriinfo = requestContext.getUriInfo();
        String path = "/" + uriinfo.getPath();

        // get timestamp
        String timestampHeader = requestContext.getHeaderString("Toshi-Timestamp");
        if (timestampHeader == null) {
            // backwards compat
            timestampHeader = requestContext.getHeaderString("Token-Timestamp");
            if (timestampHeader == null) {
                throw new WebApplicationException(buildError(400, "bad_arguments", "Missing Toshi-Timestamp"));
            }
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (NumberFormatException e) {
            throw new WebApplicationException(buildError(400, "invalid_timestamp", "Given Toshi-Timestamp is invalid"));
        }
        long desync = Math.abs(timestamp - getServerTime());
        if (desync >= TIMESTAMP_EXPIRY) {
            throw new WebApplicationException(buildError(400, "invalid_timestamp", "The difference between the timestamp and the current time is too large"));
        }

        // get id address
        String toshiId = requestContext.getHeaderString("Toshi-ID-Address");
        if (toshiId == null) {
            // backwards compat
            toshiId = requestContext.getHeaderString("Token-ID-Address");
            if (toshiId == null) {
                throw new WebApplicationException(buildError(400, "bad_arguments", "Missing Toshi-ID-Address"));
            }
            requestContext.getHeaders().add("Toshi-ID-Address", toshiId);
        }

        // get signature
        String signature = requestContext.getHeaderString("Toshi-Signature");
        if (signature == null) {
            // backwards compat
            signature = requestContext.getHeaderString("Token-Signature");
            if (signature == null) {
                throw new WebApplicationException(buildError(400, "bad_arguments", "Missing Toshi-Signature"));
            }
        }

        // get body
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream in = requestContext.getEntityStream();
        byte[] requestBytes = null;
        try {
            if (in.available() > 0) {
                copy(in, out);
                requestBytes = out.toByteArray();
                requestContext.setEntityStream(new ByteArrayInputStream(requestBytes));
            }

        } catch (IOException ex) {
            ex.printStackTrace();
            throw new WebApplicationException(buildError(500, "unexpected_error", "Unexpected error processing request"));
        }

        String body = "";
        if (requestBytes != null) {
            body = new String(requestBytes, UTF8_CHARSET);
        }

        String recAddress;
        try {
            recAddress = getRecoveredEthAddress(method, path, body, timestampHeader, signature);
        } catch (JsonProcessingException | SignatureException | InvalidComponentsException | SignatureLengthException e) {
            e.printStackTrace();
            throw new WebApplicationException(buildError(400, "invalid_signature (case 1)", "Invalid Toshi-Signature"));
        }

        if (!recAddress.equals(toshiId)) {
            throw new WebApplicationException(buildError(400, "invalid_signature (case 2)", "Invalid Toshi-Signature"));
        }
    }

    private class InvalidComponentsException extends Exception {}
    private class SignatureLengthException extends Exception {
        public SignatureLengthException(Integer actualLength){
            super();
        }
    }
}
