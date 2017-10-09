package org.janelia.alignment.protocol.s3;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.google.common.net.MediaType;

import ij.ImagePlus;

import java.awt.Image;
import java.awt.Toolkit;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wrapper to the ij.io.Opener for render.
 *
 * This is a kludge as -Djava.protocol.handler.pkgs=org.janelia.alignment.protocol does not seem to work with Jetty.
 */
public class S3Opener extends ij.io.Opener {

    private final AWSCredentialsProvider credentialsProvider;

    public S3Opener(final AWSCredentialsProvider credentialsProvider) {
        super();
        this.credentialsProvider = credentialsProvider;
    }

    @Override
    public ImagePlus openURL(final String url) {

        ImagePlus imagePlus = null;

        if (url.startsWith("s3://")) {

            String name = "";
            final int index = url.lastIndexOf('/');
            if (index > 0) {
                name = url.substring(index + 1);
            }

            try {

                final URLStreamHandler handler = new S3Handler(credentialsProvider);
                final URL u = new URL(null, url, handler);
                final URLConnection uc = u.openConnection();

                // assumes content type is always available, should be ok
                final MediaType contentType = MediaType.parse(uc.getContentType());

                final String lowerCaseUrl = url.toLowerCase(Locale.US);

                // honor content type over resource naming conventions, check for most common source image types first
                if (contentType.equals(MediaType.TIFF)) {
                    imagePlus = super.openTiff(u.openStream(), name);
                } else if (contentType.equals(MediaType.PNG)) {
                    imagePlus = openPngUsingURL(name, u);
                } else if (contentType.equals(MediaType.JPEG) || contentType.equals(MediaType.GIF)) {
                    imagePlus = openJpegOrGifUsingURL(name, u);
                } else if (lowerCaseUrl.endsWith(".tif") || lowerCaseUrl.endsWith(".tiff")) {
                    imagePlus = super.openTiff(u.openStream(), name);
                } else if (lowerCaseUrl.endsWith(".png")) {
                    imagePlus = openPngUsingURL(name, u);
                } else if (lowerCaseUrl.endsWith(".jpg") || lowerCaseUrl.endsWith(".gif")) {
                    imagePlus = openJpegOrGifUsingURL(name, u);
                } else {
                    throw new IOException("unsupported content type " + contentType + " for " + url);
                }

            } catch (final Throwable t) {
                // null imagePlus will be returned and handled upstream, no need to raise exception here
                LOG.error("failed to load " + url, t);
            }

        } else {
            imagePlus = super.openURL(url);
        }

        return imagePlus;
    }

    /* The following are based on protected methods from ij.io.Opener. */
    private ImagePlus openJpegOrGifUsingURL(final String title,
                                            final URL url) {
        final Image img = Toolkit.getDefaultToolkit().createImage(url);
        return new ImagePlus(title, img);
    }

    ImagePlus openPngUsingURL(final String title,
                              final URL url)
            throws IOException {
        final Image img;
        final InputStream in = url.openStream();
        img = ImageIO.read(in);
        return new ImagePlus(title, img);
    }

    private static final Logger LOG = LoggerFactory.getLogger(S3Opener.class);
}
