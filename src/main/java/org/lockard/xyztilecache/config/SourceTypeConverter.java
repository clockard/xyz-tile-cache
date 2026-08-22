package org.lockard.xyztilecache.config;

import java.util.Locale;
import org.lockard.xyztilecache.model.Layer;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Binds {@code xyz.layers[*].sourceType} from configuration, accepting the legacy spelling of
 * {@link Layer.SourceType#PMTILES}.
 *
 * <p>That constant was called {@code VECTOR_PMTILES} back when PMTiles archives were assumed to
 * hold vector tiles. The default enum binder matches on the constant's name, so without this an
 * existing {@code application.yml} would fail to start after the rename.
 */
@Component
@ConfigurationPropertiesBinding
public class SourceTypeConverter implements Converter<String, Layer.SourceType> {

  private static final String LEGACY_PMTILES = "VECTOR_PMTILES";

  @Override
  public Layer.SourceType convert(String source) {
    // Relaxed binding means this may arrive as "vector-pmtiles" or "vector_pmtiles" from an
    // environment variable as readily as from YAML.
    String normalised = source.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    if (LEGACY_PMTILES.equals(normalised)) {
      return Layer.SourceType.PMTILES;
    }
    return Layer.SourceType.valueOf(normalised);
  }
}
