package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for updating section z values.
 *
 * @author Eric Trautman
 */
public class SectionUpdateClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(names = "--stack", description = "Stack name", required = true)
        public String stack;

        @Parameter(names = "--sectionId", description = "Section ID", required = true)
        public String sectionId;

        @Parameter(names = "--z", description = "Z value", required = true)
        public Double z;
    }

    /**
     * @param  args  see {@link Parameters} for command line argument details.
     */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final SectionUpdateClient client = new SectionUpdateClient(parameters);
                client.updateZ();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;

    public SectionUpdateClient(final Parameters parameters) {
        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
    }

    public void updateZ()
            throws Exception {
        renderDataClient.ensureStackIsInLoadingState(parameters.stack, null);
        renderDataClient.updateZForSection(parameters.stack, parameters.sectionId, parameters.z);
    }

    private static final Logger LOG = LoggerFactory.getLogger(SectionUpdateClient.class);
}
