package nzi.fhir.validator.core.model;

import io.vertx.core.http.HttpServerRequest;
import java.util.ArrayList;

/**
 * @author Md Nazrul Islam
 */
public class ValidationRequestOptions {

    private final ArrayList<String> profilesToValidate;

    public  ValidationRequestOptions(ArrayList<String> profilesToValidate) {
        this.profilesToValidate = profilesToValidate;
    }
    public static ValidationRequestOptions fromRequest(HttpServerRequest request){
        ArrayList<String> profilesToValidate = new ArrayList<>();
        String profileUrl = request.getParam("profile", "");
        String[] profiles = profileUrl.split(",");
        for (String profile : profiles) {
            if (profile != null && !profile.trim().isEmpty()) {
                profilesToValidate.add(profile.trim());
            }
        }
        return new ValidationRequestOptions(profilesToValidate);
    }

    public ArrayList<String> getProfilesToValidate() {
        return profilesToValidate;
    }
}
