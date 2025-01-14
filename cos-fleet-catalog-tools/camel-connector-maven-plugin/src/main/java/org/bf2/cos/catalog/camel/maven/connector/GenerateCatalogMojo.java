package org.bf2.cos.catalog.camel.maven.connector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.bf2.cos.catalog.camel.maven.connector.model.ConnectorDefinition;
import org.bf2.cos.catalog.camel.maven.connector.support.Annotation;
import org.bf2.cos.catalog.camel.maven.connector.support.Connector;
import org.bf2.cos.catalog.camel.maven.connector.support.KameletsCatalog;
import org.bf2.cos.catalog.camel.maven.connector.support.MojoSupport;

import com.fasterxml.jackson.databind.node.ObjectNode;

import static java.util.Optional.ofNullable;
import static org.bf2.cos.catalog.camel.maven.connector.support.CatalogSupport.JSON_MAPPER;
import static org.bf2.cos.catalog.camel.maven.connector.support.CatalogSupport.addRequired;
import static org.bf2.cos.catalog.camel.maven.connector.support.CatalogSupport.asKey;
import static org.bf2.cos.catalog.camel.maven.connector.support.CatalogSupport.computeActions;
import static org.bf2.cos.catalog.camel.maven.connector.support.CatalogSupport.computeDataShapes;
import static org.bf2.cos.catalog.camel.maven.connector.support.CatalogSupport.computeErrorHandler;
import static org.bf2.cos.catalog.camel.maven.connector.support.CatalogSupport.copyProperties;
import static org.bf2.cos.catalog.camel.maven.connector.support.CatalogSupport.dataShape;
import static org.bf2.cos.catalog.camel.maven.connector.support.CatalogSupport.kameletType;

@Mojo(name = "generate-catalog", defaultPhase = LifecyclePhase.GENERATE_RESOURCES, threadSafe = true, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class GenerateCatalogMojo extends AbstractMojo {

    @Parameter(defaultValue = "false", property = "cos.catalog.skip")
    private boolean skip = false;

    @Parameter(defaultValue = "false", property = "cos.connector.groups")
    private boolean groups = false;

    @Parameter(readonly = true, defaultValue = "${project}")
    private MavenProject project;
    @Parameter(defaultValue = "${project.basedir}/src/generated/resources/connectors")
    private String outputPath;
    @Parameter
    private List<Annotation> annotations;

    @Parameter(defaultValue = "${session}", readonly = true)
    protected MavenSession session;
    @Parameter
    private Connector defaults;
    @Parameter
    private List<Connector> connectors;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping generate-catalog");
        }

        try {
            final KameletsCatalog catalog = KameletsCatalog.get(project, getLog());

            for (Connector connector : MojoSupport.inject(session, defaults, connectors)) {
                generateDefinitions(catalog, connector);
            }
        } catch (Exception e) {
            throw new MojoExecutionException(e);
        }
    }

    private void generateDefinitions(KameletsCatalog catalog, Connector connector)
            throws MojoExecutionException, MojoFailureException {

        final Connector.EndpointRef kafka = connector.getKafka();
        final Connector.EndpointRef adapter = connector.getAdapter();

        if (kafka.getPrefix() == null) {
            throw new MojoExecutionException("Kamelet Kafka prefix is required");
        }
        if (!Character.isLetterOrDigit(kafka.getPrefix().charAt(kafka.getPrefix().length() - 1))) {
            throw new MojoExecutionException("Kamelet Kafka prefix should end with a letter or digit");
        }
        if (adapter.getPrefix() == null) {
            throw new MojoExecutionException("Kamelet Adapter prefix is required");
        }
        if (!Character.isLetterOrDigit(adapter.getPrefix().charAt(connector.getAdapter().getPrefix().length() - 1))) {
            throw new MojoExecutionException("Kamelet Adapter prefix should end with a letter or digit");
        }

        try {
            final ObjectNode adapterSpec = catalog.kamelet(
                    adapter.getName(),
                    adapter.getVersion());
            final ObjectNode kafkaSpec = catalog.kamelet(
                    kafka.getName(),
                    kafka.getVersion());

            final String version = ofNullable(connector.getVersion()).orElseGet(project::getVersion);
            final String name = ofNullable(connector.getName()).orElseGet(project::getArtifactId);
            final String title = ofNullable(connector.getTitle()).orElseGet(project::getName);
            final String description = ofNullable(connector.getDescription()).orElseGet(project::getDescription);
            final String type = kameletType(adapterSpec);
            final String id = name.replace("-", "_");

            ConnectorDefinition def = new ConnectorDefinition();
            def.getConnectorType().setId(id);
            def.getConnectorType().setKind("ConnectorType");
            def.getConnectorType().setIconRef("TODO");
            def.getConnectorType().setName(title);
            def.getConnectorType().setDescription(description);
            def.getConnectorType().setVersion(version);
            def.getConnectorType().getLabels().add(kameletType(adapterSpec));
            def.getConnectorType().setSchema(JSON_MAPPER.createObjectNode());
            def.getConnectorType().getSchema().put("type", "object");

            //
            // Adapter
            //

            addRequired(
                    groups,
                    adapter,
                    adapterSpec,
                    def.getConnectorType().getSchema());
            copyProperties(
                    groups,
                    adapter,
                    adapterSpec,
                    def.getConnectorType().getSchema());

            //
            // Kafka
            //

            addRequired(
                    groups,
                    kafka,
                    kafkaSpec,
                    def.getConnectorType().getSchema());
            copyProperties(
                    groups,
                    kafka,
                    kafkaSpec,
                    def.getConnectorType().getSchema());

            //
            // Steps
            //

            if (connector.getActions() != null) {
                computeActions(def, connector, catalog);
            }

            //
            // DataShape
            //

            var ds = connector.getDataShape();
            if (ds == null) {
                ds = new Connector.DataShapeDefinition();
            }

            computeDataShapes(ds, adapterSpec);

            dataShape(ds.getConsumes(), def, "consumes");
            dataShape(ds.getProduces(), def, "produces");

            //
            // ErrorHandler
            //

            if (connector.getErrorHandler() != null && connector.getErrorHandler().getStrategies() != null) {
                computeErrorHandler(def, connector);
            }

            //
            // channels
            //

            if (connector.getChannels() != null) {
                for (var ch : connector.getChannels().entrySet()) {
                    ConnectorDefinition.Channel channel = new ConnectorDefinition.Channel();
                    ConnectorDefinition.Metadata metadata = channel.getMetadata();

                    // add channel to the connector definition
                    def.getConnectorType().getChannels().add(ch.getKey());

                    def.getChannels().put(ch.getKey(), channel);

                    String image = ch.getValue().getImage();

                    if (image == null) {
                        image = String.format("%s/%s/%s:%s",
                                project.getProperties().getProperty("cos.connector.container.repository"),
                                project.getProperties().getProperty("cos.connector.container.organization"),
                                name,
                                ch.getValue().getRevision());
                    }

                    metadata.setConnectorRevision(ch.getValue().getRevision());
                    metadata.setConnectorType(type);
                    metadata.setConnectorImage(image);

                    metadata.getOperators().add(new ConnectorDefinition.Operator(
                            ch.getValue().getOperatorType(),
                            ch.getValue().getOperatorVersion()));

                    metadata.getKamelets().getAdapter().setName(adapter.getName());
                    metadata.getKamelets().getAdapter().setPrefix(adapter.getPrefix());

                    metadata.getKamelets().getKafka().setName(kafka.getName());
                    metadata.getKamelets().getKafka().setPrefix(kafka.getPrefix());

                    if (ds.getConsumes() != null) {
                        metadata.setConsumes(ds.getConsumes().getDefaultFormat());
                        metadata.setConsumesClass(ds.getConsumes().getContentClass());
                    }
                    if (ds.getProduces() != null) {
                        metadata.setProduces(ds.getProduces().getDefaultFormat());
                        metadata.setProducesClass(ds.getProduces().getContentClass());
                    }

                    if (annotations != null) {
                        for (Annotation annotation : annotations) {
                            metadata.getAnnotations().put(annotation.getName(), annotation.getValue());
                        }
                    }

                    if (connector.getActions() != null) {
                        for (Connector.ActionRef step : connector.getActions()) {
                            String sanitizedName = step.getName();
                            sanitizedName = StringUtils.removeStart(sanitizedName, "cos-");
                            sanitizedName = StringUtils.removeEnd(sanitizedName, "-action");

                            metadata.getKamelets().getProcessors().put(
                                    asKey(sanitizedName),
                                    step.getName());
                        }
                    }
                }
            }

            //
            // Write
            //

            Path out = Paths.get(outputPath);
            Path file = out.resolve(id + ".json");

            Files.createDirectories(out);

            getLog().info("Writing connector to: " + file);

            JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValue(
                    Files.newBufferedWriter(file),
                    def);

        } catch (IOException e) {
            throw new MojoExecutionException("", e);
        }
    }
}