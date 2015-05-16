package org.tribbloid.spookystuff.http;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

/**
 * Created by peng on 2/4/15.
 */
public class HttpUtils {

  private static URL dummyURL() {
    try {
      return new URL("http://www.dummy.com/");
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }

  private static URL dummyURL = dummyURL();

  public static URI uri(String s) throws URISyntaxException {

    //this solution is abandoned as it cannot handle question mark
//    URI uri;
//    try {
//      uri = new URI(s);
//    }
//    catch (URISyntaxException e) {
//      uri = new URI(URIUtil.encodePath(s));
//    }
//
//    return uri.normalize();

    try {
      return new URI(s);
    }
    catch (URISyntaxException e) {
      try {
        URL url = new URL(s);
        return new URI(url.getProtocol(), url.getAuthority(), url.getPath(), url.getQuery(), null);
      } catch (MalformedURLException ee) {
        URL url;
        try {
          url = new URL(dummyURL, s);
        } catch (MalformedURLException eee) {
          throw new RuntimeException(eee);
        }
        return new URI(null, null, url.getPath(), url.getQuery(), null); //this will generate a relative URI the string itself is relative
      }
    }
  }
}