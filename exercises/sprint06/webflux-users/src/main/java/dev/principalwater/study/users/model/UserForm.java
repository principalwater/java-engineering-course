package dev.principalwater.study.users.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class UserForm {
    @NotBlank @Size(max = 256) private String firstName;
    @NotBlank @Size(max = 256) private String lastName;
    @NotNull @Min(0) private Integer age;
    private boolean active;

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public User toUser(Long id) {
        return new User(id, firstName.strip(), lastName.strip(), age, active);
    }

    public static UserForm from(User user) {
        UserForm form = new UserForm();
        form.setFirstName(user.firstName());
        form.setLastName(user.lastName());
        form.setAge(user.age());
        form.setActive(user.active());
        return form;
    }
}
