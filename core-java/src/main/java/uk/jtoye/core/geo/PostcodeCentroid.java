package uk.jtoye.core.geo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One GB postcode unit and its WGS84 centroid (V61 {@code postcode_centroid}) — the offline
 * substrate behind every "shops near you" answer on the platform.
 *
 * <p>Sourced from OS Code-Point Open under the Open Government Licence v3; provenance,
 * accuracy and limitations are recorded in {@code core-java/src/main/resources/geo/SOURCE.md}
 * and the attribution is rendered in the public footer. There is no runtime network call and
 * no API key anywhere on this path.
 *
 * <p><strong>This table is deliberately NOT tenant-scoped and NOT audited.</strong> It is
 * public reference data: it has no {@code tenant_id}, no customer rows, and the postcode of a
 * public address is not tenant information. It is therefore exempted BY ADDITION in
 * {@code RlsContractTest.EXEMPT_TABLES} rather than by weakening that sweep. Envers auditing
 * is also omitted on purpose — a {@code _aud} mirror of 1.7 M immutable reference rows would
 * double the storage to record changes that only ever happen through a wholesale dataset
 * refresh, and would itself need an RLS exemption.
 *
 * <p>The {@link #postcode} IS the primary key, stored in the dataset's canonical form:
 * <strong>uppercased and space-stripped</strong> ({@code SE155BS}, never {@code SE15 5BS}).
 * Callers must not hand-build that key — {@link PostcodeGeocoder} owns the normalisation, and
 * upstream field widths vary (6/7/8 characters), so a padding-based parser mis-keys.
 *
 * <p>House conventions mirror {@code media/MediaAsset.java}: hand-written accessors, no
 * Lombok or code-gen on entities.
 */
@Entity
@Table(name = "postcode_centroid")
public class PostcodeCentroid {

    @Id
    @Column(name = "postcode", nullable = false, length = 8)
    private String postcode;

    @Column(name = "latitude", nullable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false)
    private double longitude;

    /** JPA requires a no-arg constructor. */
    protected PostcodeCentroid() {
    }

    public PostcodeCentroid(String postcode, double latitude, double longitude) {
        this.postcode = postcode;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public String getPostcode() {
        return postcode;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }
}
