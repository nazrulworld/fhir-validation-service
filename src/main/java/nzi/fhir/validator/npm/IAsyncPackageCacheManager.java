package nzi.fhir.validator.npm;

import java.io.IOException;
import java.io.InputStream;

import io.vertx.core.Future;
import nzi.fhir.validator.model.IgPackageName;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.utilities.npm.NpmPackage;

public interface IAsyncPackageCacheManager {
    Future<NpmPackage> loadPackage(String idAndVersion) throws FHIRException,IOException;
    Future<NpmPackage> loadPackage(IgPackageName igPackageName, boolean cacheOnly) throws FHIRException,IOException;
    Future<NpmPackage> loadPackage(IgPackageName igPackageName, boolean cacheOnly, boolean loadDependencies) throws FHIRException,IOException;
    Future<NpmPackage> addPackageToCache(InputStream content) throws IOException;
    Future<String> getPackageUrl(String packageId) throws IOException;
    Future<String> getPackageId(String canonicalBase) throws IOException;
}